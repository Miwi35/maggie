import { test, expect } from '../fixtures/index.js'
import { getCollection } from '../helpers/api.js'

/**
 * The periods (MAG-809, « Périodes et contexte du jour » part 1, extends MAG-97): API
 * only. Parts 2 to 4 extend this file; the screen's steps (part 6) go to
 * `agenda-periods-page.spec.ts`.
 *
 * The account is created for the test: its only period is the one written here, so the
 * occurrences counted are this series' and a retry starts on an account of its own.
 */

test.use({ journeyAccount: 'empty' })

interface StoredPeriod {
  id: string
  name: string
  startAt?: string
  endAt?: string
}

const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

/** Friday 2037-03-06 18:00 Paris to the next Friday, every other week. */
const GARDE = {
  name: 'Garde de Léo',
  startAt: '2037-03-06T18:00:00+01:00',
  endAt: '2037-03-13T18:00:00+01:00',
  rrule: 'FREQ=WEEKLY;INTERVAL=2',
}

/** Six weeks from Sunday 2037-03-01: the clocks change on the 29th. */
const SIX_WEEKS = `/api/periods?${new URLSearchParams({
  expand: 'true',
  'endAt[after]': '2037-03-01T00:00:00+01:00',
  'startAt[before]': '2037-04-12T00:00:00+02:00',
})}`

/** The instant as Paris reads it, `YYYY-MM-DD HH:MM`. */
function paris(iso: string): string {
  return new Date(iso).toLocaleString('sv-SE', { timeZone: 'Europe/Paris' }).slice(0, 16)
}

test('a fortnightly custody unfolds into its three occurrences over six weeks, each with the series id', async ({ api }) => {
  const agenda = await api.post('/api/agendas', { headers: JSON_LD, data: { name: 'Famille' } })
  expect(agenda.status(), await agenda.text()).toBe(201)
  const agendaIri = (await agenda.json())['@id'] as string

  const created = await api.post('/api/periods', { headers: JSON_LD, data: { ...GARDE, agenda: agendaIri } })
  expect(created.status(), await created.text()).toBe(201)
  const id = (await created.json()).id as string

  // Served from Elasticsearch: the series is findable once it is indexed.
  let occurrences: StoredPeriod[] = []
  await expect
    .poll(async () => {
      occurrences = await getCollection<StoredPeriod>(api, SIX_WEEKS)

      return occurrences.length
    }, { timeout: 30_000, message: 'the series never became findable on /api/periods?expand=true' })
    .toBe(3)

  expect(occurrences.map((occurrence) => occurrence.id)).toEqual([id, id, id])
  expect(occurrences.map((occurrence) => paris(occurrence.startAt ?? ''))).toEqual([
    '2037-03-06 18:00',
    '2037-03-20 18:00',
    '2037-04-03 18:00',
  ])
  expect(occurrences.every((occurrence) => GARDE.name === occurrence.name)).toBe(true)
})
