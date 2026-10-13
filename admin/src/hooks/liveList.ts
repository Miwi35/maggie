/**
 * What a list does with the Mercure events of its resource, without reading the
 * collection again (MAG-347).
 *
 * Collections are served from Elasticsearch, which the worker indexes after the
 * write; the event leaves with the write. A read made when the event arrives can
 * miss the change, so the event itself is applied: it wins over the read, which
 * stays the source at load. Rows are keyed by IRI — the `id` of a record from the
 * Hydra provider and the `@id` of a payload (the payload's own `id`, when it has
 * one, is the ULID).
 */

export type Row = { id: string } & Record<string, unknown>

export interface LiveConfig {
  /** The Mercure topics of the resource, e.g. `['/api/accounts/{id}']`. */
  topics: string[]
  /** Payload keys that name another resource by ULID, and where the record keeps its IRI: `accountId` → `account`. */
  references?: Record<string, { field: string; collection: string }>
  /** What a payload must carry, once translated, to be shown as a new row on its own. */
  required: string[]
  /** What a payload never carries but the list shows, for a row first seen through an event. */
  fallback?: () => Record<string, unknown>
  /** A permanent filter of the list (`filter` prop) the client can check: a new row outside it is not shown. */
  accept?: (row: Row) => boolean
}

export interface LiveEntry {
  fields: Record<string, unknown>
  fallback: Record<string, unknown>
  deleted: boolean
}

/** What the events said, by IRI. */
export type Overlay = Record<string, LiveEntry>

export interface LiveView {
  sort: { field: string; order: string }
  page: number
  perPage: number
  /** A filter is active: whether a new row matches it is the server's to say. */
  filtered: boolean
}

function iriOf(payload: Record<string, unknown>): string | null {
  if (typeof payload['@id'] === 'string') return payload['@id']
  return typeof payload.id === 'string' && payload.id.startsWith('/') ? payload.id : null
}

function translate(payload: Record<string, unknown>, config: LiveConfig): Record<string, unknown> {
  const fields: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(payload)) {
    if (key === '@id' || key === 'id' || key === 'deleted') continue
    const reference = config.references?.[key]
    if (reference) {
      fields[reference.field] = typeof value === 'string' ? `${reference.collection}/${value}` : null
    } else {
      fields[key] = value
    }
  }

  return fields
}

/** The overlay after `payload`; the same object when the payload names no row. */
export function applyEvent(overlay: Overlay, payload: unknown, config: LiveConfig): Overlay {
  if (!payload || typeof payload !== 'object') return overlay
  const event = payload as Record<string, unknown>
  const iri = iriOf(event)
  if (!iri) return overlay

  const known = overlay[iri]
  if (event.deleted === true) {
    return { ...overlay, [iri]: { fields: known?.fields ?? {}, fallback: known?.fallback ?? {}, deleted: true } }
  }

  return {
    ...overlay,
    [iri]: {
      fields: { ...known?.fields, ...translate(event, config) },
      fallback: known?.fallback ?? config.fallback?.() ?? {},
      deleted: false,
    },
  }
}

function compare(a: unknown, b: unknown): number {
  const aMissing = a === null || a === undefined
  const bMissing = b === null || b === undefined
  if (aMissing || bMissing) return Number(aMissing) - Number(bMissing)
  if (typeof a === 'string' && typeof b === 'string') return a.localeCompare(b, 'fr', { sensitivity: 'base' })

  return Number(a) - Number(b)
}

function sortRows(rows: Row[], sort: LiveView['sort']): Row[] {
  const direction = sort.order === 'DESC' ? -1 : 1

  return [...rows].sort((a, b) => direction * compare(a[sort.field], b[sort.field]))
}

export interface LiveResult {
  data: Row[]
  /** Rows the events added that the read does not hold: what to add to its total. */
  added: number
  /** Rows the events removed that the read still holds: what to take off its total. */
  removed: number
}

/**
 * `listed` (the read) with the overlay applied.
 *
 * A row the read does not hold is added only if it can stand alone and the
 * window is the one it belongs to: the first page, or inside the page — what
 * sorts before the page belongs to an earlier one, and a full page pushes its
 * last row to the next. Rows the read holds are updated or dropped wherever
 * they are.
 */
export function applyOverlay(listed: Row[], overlay: Overlay, config: LiveConfig, view: LiveView): LiveResult {
  const entries = Object.entries(overlay)
  if (entries.length === 0) return { data: listed, added: 0, removed: 0 }

  const listedIds = new Set(listed.map((row) => row.id))
  let removed = 0
  const kept: Row[] = []
  for (const row of listed) {
    const entry = overlay[row.id]
    if (entry?.deleted) removed++
    else kept.push(entry ? { ...row, ...entry.fields } : row)
  }

  const added: Row[] = []
  if (!view.filtered) {
    for (const [iri, entry] of entries) {
      if (entry.deleted || listedIds.has(iri)) continue
      if (!config.required.every((key) => key in entry.fields)) continue
      const row = { ...entry.fallback, ...entry.fields, id: iri }
      if (config.accept && !config.accept(row)) continue
      added.push(row)
    }
  }

  const touchesSort = entries.some(([, entry]) => !entry.deleted && view.sort.field in entry.fields)
  if (added.length === 0 && !touchesSort) return { data: kept, added: 0, removed }

  const addedIds = new Set(added.map((row) => row.id))
  let sorted = sortRows([...kept, ...added], view.sort)
  if (view.page > 1) {
    // What sorts first belongs to an earlier page.
    while (sorted.length > 0 && addedIds.has(sorted[0].id)) sorted = sorted.slice(1)
  }

  return { data: sorted.slice(0, view.perPage), added: added.length, removed }
}
