import { bareId } from './recurring'

/** The aisle a list line carries: its product's aisle in its store (MAG-493). */
export interface AisleSummary {
  id: string
  name: string
  position: number
}

export interface ListLine {
  id: string
  position: number
  store?: { id: string; name: string; visitOrder: number } | null
  aisle?: AisleSummary | null
}

/** The lines of one aisle of a store; `aisle` null is « Sans rayon ». */
export interface AisleSection<T extends ListLine> {
  aisle: AisleSummary | null
  items: T[]
}

export interface StoreGroup<T extends ListLine> {
  storeId: string | null
  storeName: string
  visitOrder: number
  /** Aisle by aisle, « Sans rayon » last; only one section, with no aisle, when no line has one. */
  sections: AisleSection<T>[]
  /** Every line of the store, in the order the sections draw them. */
  items: T[]
}

export const NO_AISLE = 'Sans rayon'
const UNASSIGNED = '__unassigned__'

const byPosition = <T extends ListLine>(a: T, b: T) => a.position - b.position

/**
 * A store's lines aisle by aisle, in the order of the way through the store,
 * then by their own position inside an aisle; lines with no aisle last.
 */
export function sectionsByAisle<T extends ListLine>(items: T[]): AisleSection<T>[] {
  const sections = new Map<string, AisleSection<T>>()
  for (const item of items) {
    const key = item.aisle ? bareId(item.aisle.id) : ''
    let section = sections.get(key)
    if (!section) {
      section = { aisle: item.aisle ?? null, items: [] }
      sections.set(key, section)
    }
    section.items.push(item)
  }
  for (const section of sections.values()) section.items.sort(byPosition)

  return [...sections.values()].sort((a, b) => {
    if (!a.aisle) return 1
    if (!b.aisle) return -1
    return a.aisle.position - b.aisle.position || a.aisle.name.localeCompare(b.aisle.name)
  })
}

/** The list cut by store, in visit order, each store cut by aisle. */
export function groupByStoreAndAisle<T extends ListLine>(items: T[]): StoreGroup<T>[] {
  const groups = new Map<string, { storeId: string | null; storeName: string; visitOrder: number; items: T[] }>()
  for (const item of items) {
    const key = item.store?.id || UNASSIGNED
    let group = groups.get(key)
    if (!group) {
      group = {
        storeId: item.store?.id || null,
        storeName: item.store?.name || 'Non assigné',
        visitOrder: item.store?.visitOrder ?? Number.MAX_SAFE_INTEGER,
        items: [],
      }
      groups.set(key, group)
    }
    group.items.push(item)
  }

  return [...groups.values()]
    .sort((a, b) => a.visitOrder - b.visitOrder)
    .map((group) => {
      const sections = sectionsByAisle(group.items)
      return { ...group, sections, items: sections.flatMap((s) => s.items) }
    })
}

/** True when the store's lines are worth an aisle heading: at least one has an aisle. */
export const hasAisles = <T extends ListLine>(group: StoreGroup<T>): boolean =>
  group.sections.some((section) => section.aisle !== null)
