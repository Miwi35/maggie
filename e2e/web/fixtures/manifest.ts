import { readFileSync } from 'node:fs'

/**
 * The seed manifest — the only honest way to address a seeded row.
 *
 * ULIDs carry a timestamp, so every `task e2e:seed` produces different ids for
 * identical data. `app:e2e:seed` writes `api/var/e2e/seed-manifest.json`
 * mapping each Alice reference (`e2e_task_open`, `e2e_agenda_personal`, …) to
 * the id it got, and journeys read ids from here rather than hard-coding them.
 *
 * The container mounts that directory read-only at /seed; outside it, the path
 * below resolves relative to this file.
 */

export interface SeedManifest {
  anchor: string
  references: Record<string, { class: string; id: string }>
}

const MANIFEST_PATH =
  process.env.E2E_SEED_MANIFEST ?? new URL('../../../api/var/e2e/seed-manifest.json', import.meta.url).pathname

let cached: SeedManifest | null = null

export function seedManifest(): SeedManifest {
  if (cached) {
    return cached
  }

  let contents: string
  try {
    contents = readFileSync(MANIFEST_PATH, 'utf8')
  } catch (cause) {
    throw new Error(
      `No seed manifest at ${MANIFEST_PATH} — run \`task e2e:seed\` before the journeys. (${String(cause)})`,
    )
  }

  cached = JSON.parse(contents) as SeedManifest

  return cached
}

/** The id the seed gave an Alice reference, e.g. `seedId('e2e_task_open')`. */
export function seedId(reference: string): string {
  const entry = seedManifest().references[reference]

  if (!entry) {
    throw new Error(
      `The seed manifest has no reference '${reference}'. ` +
        'Check api/fixtures/e2e/ — a renamed reference breaks every journey that reads it.',
    )
  }

  return entry.id
}

/**
 * The date the seed anchored on, as `YYYY-MM-DD` — the day in Paris.
 *
 * Read this rather than the wall clock: a seed and a journey either side of
 * midnight in Paris disagree, and anyone passing `--now` deliberately would break
 * assertions that had no reason to care.
 */
export function seedAnchorDate(): string {
  return seedManifest().anchor.slice(0, 10)
}

/**
 * A day relative to the anchor, as `YYYY-MM-DD` — what `<e2eDate("+2 days")>`
 * produces on the fixture side.
 *
 * The arithmetic is done in UTC on a calendar date on purpose. `new Date(y, m, d)`
 * would build the day in the *browser's* zone and then shift it across the DST
 * change in Paris, so a journey run in the last week of October would write its
 * event one day away from the fixture it is supposed to sit beside.
 */
export function seedDate(offsetDays = 0): string {
  const [year, month, day] = seedAnchorDate().split('-').map(Number)

  return new Date(Date.UTC(year, month - 1, day + offsetDays)).toISOString().slice(0, 10)
}
