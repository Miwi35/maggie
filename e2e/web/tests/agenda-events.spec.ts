import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { expectRealtimeSync, openSubscribed } from '../helpers/mercure.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * Creating, changing and deleting an event from the agenda (MAG-100).
 *
 * The module MAG-93 found the most regressive in the repository, and what it found
 * here is about what the screen *sends* rather than what it shows: `c359b43`
 * doubled the agenda's Hydra IRI on creation and broke every event, and
 * `76017dd`/`dfad086` lost a multi-day event on every day but its first. So the
 * assertions below end on the API for the write and on the grid for the display,
 * and never on a notification — a toast says a request was made, not that it
 * stored what the owner typed.
 *
 * Two conventions hold throughout, and both exist to keep the suite parallel-safe:
 *
 * - **Every test writes its own summary.** Playwright runs a file's tests across
 *   workers at the same time, so two of them looking for the same title would read
 *   each other's row and pass for the wrong reason.
 * - **Everything happens on the anchor's own day** (`seedDate()`), and chips are
 *   asserted in the day view. The grid opens on the current month, so an event a
 *   day later can fall into the next one and be invisible without anyone
 *   navigating; and month view folds a day holding more than three events behind
 *   "+N autres", which makes a count there a count of what fitted.
 */

interface StoredEvent {
  id?: string
  summary?: string
  startAt?: string
  endAt?: string
  agenda?: string
  allDay?: boolean
  status?: string
  reminders?: { useDefault: boolean; overrides: { method: string; minutes: number }[] } | null
}

/** The anchor's own day — the fixtures' "today", and always on screen. */
const DAY = seedDate()

/**
 * A 15:00–16:00 slot on the anchor's day, under a title the current attempt alone
 * will write.
 *
 * CI retries once and nothing reseeds in between, so a retry starts on the row the
 * first attempt already created — and a chip count of one then reads two and fails
 * on a run where nothing is wrong. The name carries the attempt rather than the
 * file turning retries off, so a real flake still gets its second chance.
 */
function slot(summary: string): { summary: string; start: string; end: string } {
  const { retry } = test.info()

  return {
    summary: 0 === retry ? summary : `${summary} (essai ${retry})`,
    start: `${DAY}T15:00`,
    end: `${DAY}T16:00`,
  }
}

async function storedEvent(api: APIRequestContext, summary: string): Promise<StoredEvent> {
  return waitForIndexed<StoredEvent>(api, '/api/events?itemsPerPage=100', (event) => event.summary === summary, {
    what: `The event "${summary}"`,
  })
}

test('an event created from the dialog names its agenda with one IRI, not two', async ({ page, api }) => {
  const created = slot('Café avec Léa')
  const calendar = new CalendarPage(page)
  await calendar.open()

  // Deliberately not the default agenda: "Famille" proves the select was read,
  // where "Perso" would pass on a dialog that ignored it entirely.
  await calendar.createEvent({ ...created, agenda: 'Famille' })

  const stored = await storedEvent(api, created.summary)

  // `c359b43`, in one line: `calendarId` already *is* an IRI from the Hydra data
  // provider, and the dialog used to wrap it in `/api/calendars/` again. The API
  // then refused the lot. Anything but one `/api/agendas/<ulid>` is that bug.
  expect(stored.agenda).toBe(`/api/agendas/${seedId('e2e_agenda_shared')}`)
  expect(stored.allDay).toBe(false)

  // And it is reachable where an owner would look for it — the deep link the
  // admin's own search uses, which goes to the event's own date.
  await calendar.openEvent(String(stored.id), created.summary)
})

/** The hour the owner typed is the hour that is stored (MAG-168). */
test('the hour typed is the hour stored', async ({ page, api }) => {
  const created = slot('Apéro chez Sam')
  const calendar = new CalendarPage(page)
  await calendar.open()

  await calendar.createEvent({ ...created, agenda: 'Perso' })

  const stored = await storedEvent(api, created.summary)
  const inParis = new Date(String(stored.startAt)).toLocaleString('fr-FR', {
    timeZone: 'Europe/Paris',
    hour: '2-digit',
    minute: '2-digit',
  })

  expect(inParis).toBe('15:00')
})

test('the pencil renames an event, and the bin removes it for good', async ({ page, api }) => {
  const created = slot('Réunion de copropriété')
  const renamed = 'Réunion de copropriété (reportée)'
  const calendar = new CalendarPage(page)
  await calendar.open()

  await calendar.createEvent({ ...created, agenda: 'Perso' })
  const stored = await storedEvent(api, created.summary)

  // Reached through the deep link, so this does not depend on which month the
  // grid happened to open on.
  await calendar.openEvent(String(stored.id), created.summary)
  await calendar.editFromPopover()
  await calendar.submitEventEdit({ summary: renamed })

  // The same row, renamed — not a second one.
  const afterEdit = await storedEvent(api, renamed)
  expect(afterEdit.id).toBe(stored.id)

  await calendar.openEvent(String(afterEdit.id), renamed)
  await calendar.deleteFromPopover()

  // The index, not the database: the grid is served from Elasticsearch, so an
  // event deleted from Postgres alone is still on screen — which is MAG-164, and
  // `tests/agenda-delete.spec.ts` is what holds it through the API. Here the point
  // is that the bin on the card reaches the same place.
  await expect
    .poll(
      async () =>
        (await getCollection<StoredEvent>(api, '/api/events?itemsPerPage=100')).some((e) => e.id === afterEdit.id),
      { timeout: 30_000, message: 'the deleted event should stop being findable' },
    )
    .toBe(false)
})

/**
 * A reminder set in the dialog is stored in the shape the cron reads (MAG-121).
 *
 * Reminders used to be filled by Google's import alone: the dialog had no field,
 * and the API's POST dropped them in silence even when a client sent them — so the
 * smoke journey needed a second PATCH for its one event. What this asserts is the
 * stored shape rather than the field on screen, because `{useDefault, overrides}`
 * is the only one `maggie:notification:check-reminders` looks at; a bare list
 * stores fine and reminds nobody, and the whole chain stays green.
 *
 * The firing itself has no browser surface — the producer is a cron — and
 * `e2e/smoke/smoke.sh` step 11 is where that half is proved, for a one-off event
 * and for an occurrence of a series.
 */
test('a reminder set in the dialog is stored, and shown on the card', async ({ page, api }) => {
  const created = slot('Rendez-vous chez le notaire')
  const calendar = new CalendarPage(page)
  await calendar.open()

  await calendar.createEvent({ ...created, agenda: 'Perso', reminder: '1 heure avant' })

  const stored = await storedEvent(api, created.summary)
  expect(stored.reminders).toEqual({
    useDefault: false,
    overrides: [{ method: 'popup', minutes: 60 }],
  })

  // And the owner can see what he will be told, and when, without reopening the form.
  await calendar.openEvent(String(stored.id), created.summary)
  await expect(calendar.reminderOnCard('1 heure avant')).toBeVisible()
})

/**
 * A tentative event is stored as such and drawn apart (MAG-246).
 *
 * The status is chosen in the dialog and read back off the card: what is asserted is the
 * stored `tentative` and the hatched chip, then that the pencil puts it back to confirmed
 * and the chip goes back to normal.
 */
test('an event created as « Provisoire » is stored tentative, shown as such, and can be confirmed', async ({
  page,
  api,
}) => {
  const created = slot('Déjeuner de principe')
  const calendar = new CalendarPage(page)
  await calendar.open()

  await calendar.createEvent({ ...created, agenda: 'Perso', status: 'Provisoire' })

  const stored = await storedEvent(api, created.summary)
  expect(stored.status).toBe('tentative')

  await calendar.chooseView('Jour')
  await expect(calendar.tentativeChip(created.summary)).toHaveCount(1)

  await calendar.openEvent(String(stored.id), created.summary)
  await expect(calendar.popover.getByText('Provisoire', { exact: true })).toBeVisible()

  await calendar.editFromPopover()
  await calendar.submitEventEdit({ status: 'Confirmé' })

  await expect.poll(async () => (await storedEvent(api, created.summary)).status).toBe('confirmed')
  await calendar.openEvent(String(stored.id), created.summary)
  await expect(calendar.popover.getByText('Provisoire', { exact: true })).toHaveCount(0)
})

/**
 * Renaming an event does not throw its reminder away.
 *
 * The edit form sends the reminders it holds on every save, so a form that opened
 * empty would quietly clear everything the owner had set from Maggie or imported
 * from Google — on an edit that had nothing to do with reminders.
 *
 * Its own event rather than the seeded dentist appointment, which carries a
 * reminder too: `tests/mag-130-controls.spec.ts` renames that row through the same
 * dialog, and two files renaming one row read each other's result.
 */
test('renaming an event keeps the reminder it already had', async ({ page, api }) => {
  const created = slot('Révision de la voiture')
  const renamed = `${created.summary} — reportée`
  const calendar = new CalendarPage(page)
  await calendar.open()

  await calendar.createEvent({ ...created, agenda: 'Perso', reminder: '30 minutes avant' })
  const stored = await storedEvent(api, created.summary)

  await calendar.openEvent(String(stored.id), created.summary)
  await calendar.editFromPopover()
  // Nothing said about the reminders: the form keeps the ones it was opened with.
  await calendar.submitEventEdit({ summary: renamed })

  const afterEdit = await storedEvent(api, renamed)
  expect(afterEdit.id).toBe(stored.id)
  expect(afterEdit.reminders).toEqual({
    useDefault: false,
    overrides: [{ method: 'popup', minutes: 30 }],
  })
})

/**
 * A multi-day event shows on every day it covers.
 *
 * `76017dd` and `dfad086` are the mobile halves of this — a week view that placed
 * an event on its start date only, and a day view that did the same. The web grid
 * gets the layout from FullCalendar rather than from our own code, so what this
 * really guards is the data behind it: the seeded night train runs 21:00 to 08:00
 * the next morning, and an `endAt` the API truncated, or a range query that only
 * looked at `startAt`, would leave it on one day.
 *
 * Walked day by day rather than counted in the week view: how FullCalendar lays a
 * crossing event out is its business, "it is on both days" is the promise.
 */
test('a night-train event is on the day it starts and on the day it ends', async ({ page }) => {
  const calendar = new CalendarPage(page)
  const summary = 'Train de nuit pour Vienne'

  await calendar.goToEventDate(seedId('e2e_event_multi_day'), summary)
  await calendar.chooseView('Jour')
  await expect(calendar.chip(summary), 'missing on the evening it leaves').toHaveCount(1)

  await calendar.goForward()
  await expect(calendar.chip(summary), 'missing on the morning it arrives').toHaveCount(1)
})

/**
 * Two windows of the same owner, one event created in one of them.
 *
 * The calendar subscribes to `/api/events/{id}` and refetches on every update, so
 * this is the agenda's half of what `tests/mercure.spec.ts` proves about the hub.
 * The observing window must not navigate — `expectRealtimeSync` fails if it does,
 * because a reload would satisfy the assertion while real-time was dead (b16916d).
 */
test('an event created in one window appears in the other without a reload', async ({
  twoWindows,
  api,
}) => {
  const created = slot('Visite de l’appartement')
  const { actor, observer } = twoWindows
  const actorCalendar = new CalendarPage(actor)
  const observerCalendar = new CalendarPage(observer)

  await actorCalendar.open()
  // Subscribed before anything is published: an update sent while the hub has not
  // registered the subscriber is never delivered, and that is the single most
  // common way a real-time journey goes flaky.
  await openSubscribed(observer, () => observerCalendar.open())
  // The day the event lands on, so one chip is one occurrence and nothing folds.
  await observerCalendar.chooseView('Jour')

  await expectRealtimeSync(
    observer,
    async () => {
      await actorCalendar.createEvent({ ...created, agenda: 'Perso' })
      // The chip can only appear once the row is indexed: the refetch the Mercure
      // update triggers reads Elasticsearch, so the publication alone is not
      // enough for the observing window to see anything.
      await storedEvent(api, created.summary)
    },
    async () => {
      await expect(observerCalendar.chip(created.summary)).toHaveCount(1)
    },
  )
})
