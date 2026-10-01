import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * A weekly series, and single occurrences of it changed on their own (MAG-100).
 *
 * This is the case the ticket exists for, and writing it found MAG-169: the API
 * never returns `recurringEvent` or `originalStartAt`, because `toSearchDocument()`
 * does not write them and the Elasticsearch providers rebuild the entity from the
 * indexed document alone. The admin's exception map is therefore always empty, so
 * an overridden occurrence is drawn *twice* — once as the master's occurrence at
 * the old time, once as the exception at the new one — and a cancelled one is drawn
 * as an ordinary event instead of being removed.
 *
 * The two assertions that depend on that are marked expected-to-fail with the
 * ticket's number rather than left out: an exemption nobody wrote down is a missing
 * test, and each marker turns red the day the fix lands, which is when it has to
 * go. Everything that does *not* depend on it — the write each dialog makes, the
 * master left alone — is asserted for real.
 *
 * The three tests that write act on three different occurrences of the one seeded
 * series, so the file stays safe to run in parallel. A serial group would replay
 * whole on a retry, on data the first attempt had already moved.
 */

interface StoredEvent {
  id?: string
  summary?: string
  startAt?: string
  status?: string
  rrule?: string
}

const MASTER = 'Cours de piano'
const MASTER_RRULE = 'FREQ=WEEKLY;COUNT=8'

/**
 * `e2e_event_recurring` starts on the anchor + 2 days, weekly, eight times — so its
 * lessons fall on +2, +9, +16, +23, +30, +37, +44 and +51.
 */
const OCCURRENCES = {
  /** The master's own date, where the override test writes. */
  first: seedDate(2),
  /** Overridden by `e2e_event_recurring_exception`, moved to 19:00. */
  moved: seedDate(16),
  /** Refused by `e2e_event_recurring_cancelled`. */
  cancelled: seedDate(23),
}

const MOVED_SUMMARY = 'Cours de piano (décalé)'

async function events(api: APIRequestContext): Promise<StoredEvent[]> {
  return getCollection<StoredEvent>(api, '/api/events?itemsPerPage=100')
}

async function master(api: APIRequestContext): Promise<StoredEvent | undefined> {
  return (await events(api)).find((event) => event.id === seedId('e2e_event_recurring'))
}

/**
 * A title the current attempt alone will write.
 *
 * CI retries once and nothing reseeds in between, so a retry starts on the row the
 * first attempt already created — and "exactly one" then reads two and fails on a
 * run where nothing is wrong. Counting is the point here (an exception instance has
 * to be written once, not once per OK), so the name carries the attempt rather than
 * the file turning retries off.
 */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} (essai ${retry})`
}

/** The calendar day an instant falls on in Paris, as `YYYY-MM-DD`. */
function parisDay(iso: string): string {
  return new Date(iso).toLocaleDateString('fr-CA', { timeZone: 'Europe/Paris' })
}

/**
 * An occurrence of the series nothing else touches, one per attempt.
 *
 * The eight lessons fall on the anchor plus 2, 9, 16, 23, 30, 37, 44 and 51 days.
 * Three are taken: +2 is where the override test writes, +16 carries the seeded
 * moved occurrence and +23 the seeded cancelled one. That leaves +9 for the first
 * attempt and +30 for the retry — three weeks apart, so neither can see the other's
 * work however the grid rounds a week.
 *
 * @returns how many weeks forward from the master's own, and the day that lands on
 */
function freeOccurrence(): { week: number; day: string } {
  const week = 1 + 3 * test.info().retry

  return { week, day: seedDate(2 + 7 * week) }
}

test('the seeded series shows one occurrence a week', async ({ page }) => {
  const calendar = new CalendarPage(page)

  await calendar.goToEventDate(seedId('e2e_event_recurring'), MASTER)
  await calendar.chooseView('Semaine')

  // One lesson that week, and it is the master's own: `FREQ=WEEKLY;COUNT=8` from
  // the anchor plus two days. A series that failed to expand would show none; one
  // expanded with the wrong interval would show several.
  await expect(calendar.chipsOnDay(OCCURRENCES.first, MASTER)).toHaveCount(1)
})

/**
 * The two overrides the seed carries, read on the grid.
 *
 * Read-only and fed entirely from fixtures, which is what makes it the right place
 * for MAG-169's marker: no write of its own, so it fails for one reason only. The
 * navigation it does share with the tests around it — `goToEventDate`, `chooseView` —
 * is driven unmarked by the first test, so a broken page object cannot hide behind
 * this marker.
 */
test('an overridden occurrence replaces the original, and a refused one disappears', async ({ page }) => {
  test.fail()

  const calendar = new CalendarPage(page)

  await calendar.goToEventDate(seedId('e2e_event_recurring_exception'), MOVED_SUMMARY)
  await calendar.chooseView('Semaine')

  await expect(calendar.chipsOnDay(OCCURRENCES.moved, MOVED_SUMMARY), 'the moved lesson is missing').toHaveCount(1)
  await expect(
    calendar.chipsOnDay(OCCURRENCES.moved, MASTER),
    'the lesson it replaced is still on the grid at its old time',
  ).toHaveCount(0)

  // One week on sits the cancelled override. Nothing at all should be there.
  await calendar.goForward()
  await expect(
    calendar.chipsOnDay(OCCURRENCES.cancelled, MASTER),
    'the refused lesson is still on the grid',
  ).toHaveCount(0)
})

/**
 * Changing one occurrence writes an exception instance and leaves the master alone.
 *
 * Asserted on the API, which is where the difference between the three answers to
 * "cet événement / et les suivants / tous" actually lands: one creates a row, one
 * truncates the RRULE, one moves the series. Getting that wrong is silent on a grid
 * that would look plausible either way.
 *
 * "Cet événement et tous les suivants" is not here on purpose: it writes on the
 * master, which the two tests below read, and it is already covered field by field
 * in `admin/src/modules/calendar/CalendarView.test.tsx`.
 */
test('changing one occurrence adds an exception and leaves the series as it was', async ({ page, api }) => {
  const renamed = perAttempt('Cours de piano (salle 2)')
  const calendar = new CalendarPage(page)

  await calendar.goToEventDate(seedId('e2e_event_recurring'), MASTER)
  await calendar.chooseView('Semaine')

  await calendar.openChip(MASTER)
  await calendar.editFromPopover()
  await calendar.submitEventEdit({ summary: renamed })
  await calendar.chooseRecurrenceScope('Cet événement')

  // One new row, and only one: a second would mean the dialog wrote on every OK.
  await expect
    .poll(async () => (await events(api)).filter((event) => event.summary === renamed).length, {
      timeout: 30_000,
      message: 'the override should be stored as exactly one new event',
    })
    .toBe(1)

  // The series itself is untouched — same title, same rule. "Tous les événements"
  // would have renamed the master instead, and nothing on screen would say so.
  const series = await master(api)
  expect(series?.summary).toBe(MASTER)
  expect(series?.rrule).toBe(MASTER_RRULE)
})

/**
 * Deleting one occurrence cancels it and keeps the rest of the series.
 *
 * "Cet événement" on a deletion does not delete anything: it writes a `cancelled`
 * exception for that date, which is how Google's model refuses one occurrence
 * without touching the rule. Whether the grid then stops drawing it is MAG-169,
 * asserted above on the seeded pair; here the point is that the bin writes the
 * right row and spares the series.
 */
test('deleting one occurrence cancels it and keeps the others', async ({ page, api }) => {
  const calendar = new CalendarPage(page)

  // A different week on every attempt, and this is the one write `perAttempt()`
  // cannot protect: a cancelled exception keeps its parent's exact summary, so the
  // name carries nothing. Worse, MAG-169 draws it as an ordinary chip — so a retry
  // on the same week would read two "Cours de piano" before touching anything and
  // fail on the pre-assertion, blaming the seed. Each attempt therefore refuses an
  // occurrence no other attempt has been near.
  const { week, day } = freeOccurrence()

  await calendar.goToEventDate(seedId('e2e_event_recurring'), MASTER)
  await calendar.chooseView('Semaine')
  for (let step = 0; step < week; step++) {
    await calendar.goForward()
  }
  await expect(calendar.chipsOnDay(day, MASTER)).toHaveCount(1)

  await calendar.openChip(MASTER)
  await calendar.deleteFromPopover()
  await calendar.chooseRecurrenceScope('Cet événement')

  // Pinned to this occurrence's own date, not merely to "a cancelled lesson
  // exists": the seed already holds one cancelled override, so that question
  // answered yes before the test ran a line.
  await expect
    .poll(
      async () =>
        (await events(api)).some(
          (event) =>
            event.summary === MASTER &&
            event.status === 'cancelled' &&
            parisDay(String(event.startAt)) === day,
        ),
      { timeout: 30_000, message: 'the refused occurrence should be stored as a cancelled exception on its own date' },
    )
    .toBe(true)

  const series = await master(api)
  expect(series?.rrule).toBe(MASTER_RRULE)
  expect(series?.status).toBe('confirmed')
})
