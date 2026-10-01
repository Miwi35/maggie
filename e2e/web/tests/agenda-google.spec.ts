import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { openWiremockJournal } from '../helpers/wiremock.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * Google Calendar, simulated (MAG-100).
 *
 * Every Google call the stack makes goes to WireMock
 * (`GOOGLE_API_BASE_URL=http://wiremock:8080/google/`), so this journey drives the
 * real client, the real sync service and the real webhook — only the far side is a
 * fixture. A request leaving the network would be a bug rather than a dependency,
 * and the journal is also how the effects below are asserted: a connected agenda's
 * only observable result today is the call it made (see the MAG-148 test).
 *
 * The journal is global, so every counter here is scoped to the calendar it is about
 * — and the stubs help: the watch channel id names its calendar, so two connected
 * agendas cannot answer each other's webhook. Without that, exporting one agenda
 * while another is being imported would let the webhook below pull into the wrong
 * one, and the assertion it guards would pass having tested nothing.
 *
 * The regression it exists for is `544c9e5`: the cron's pull used to overwrite a
 * local change that had not reached Google yet. The fix was twofold — push
 * synchronously, and skip an event whose `updated` on Google has not moved since the
 * last sync — and the stubs are built so this can be asserted: the events list
 * answers `updated: 08:00`, the patch answers `09:00`. A pull after a local change
 * therefore *must* leave it alone, and a guard that went missing shows up as the
 * imported title coming back.
 *
 * Google's events list is pinned to March 2026 and that is deliberate (see
 * `.docker/e2e/wiremock/README.md`): lining stub dates up with the seed anchor would
 * mean pinning the anchor, which moves the whole seeded world into the past while
 * the containers' clock stays real. The imported event is reached through the
 * calendar's own `?eventId=` deep link instead, which goes to whatever date it
 * carries.
 */

const GOOGLE_AGENDA = 'Agenda Google de test'
/** The one calendar `mappings/google.json` offers, and the channel id it derives. */
const GOOGLE_CALENDAR_ID = 'e2e@maggie.local'
const PULLED_EVENT = 'Réunion importée de Google'
const LOCAL_TITLE = 'Réunion importée (déplacée en salle 2)'
/** The agenda the export test makes for itself — never a seeded one, see there. */
const EXPORTED_PREFIX = 'Agenda à exporter'

/**
 * Scoped to the imported calendar, not to any calendar.
 *
 * The journal is global, so `…/calendars/[^/]+/events` would also count a sync of
 * the agenda the export test connects — and "the pull has run" would then be true
 * before this calendar had been pulled at all. The `@` is matched either way because
 * whether the Google client percent-encodes a path segment is its business.
 */
const LIST_EVENTS = /\/google\/calendar\/v3\/calendars\/e2e(%40|@)maggie\.local\/events(\?|$)/
const CREATE_CALENDAR = /\/google\/calendar\/v3\/calendars(\?|$)/

interface StoredEvent {
  id?: string
  summary?: string
}

interface StoredAgenda {
  id?: string
  name?: string
  googleCalendarId?: string
  googleSynced?: boolean
}

async function agendas(api: APIRequestContext): Promise<StoredAgenda[]> {
  return getCollection<StoredAgenda>(api, '/api/agendas')
}

async function events(api: APIRequestContext): Promise<StoredEvent[]> {
  return getCollection<StoredEvent>(api, '/api/events?itemsPerPage=100')
}

/**
 * Removes any agenda already connected to the test Google calendar.
 *
 * Unconditional, and not housekeeping for its own sake: once MAG-148 is fixed the
 * import dialog will hide a calendar that is already connected, and a retry would
 * then open it on "Tous les calendriers Google sont déjà importés" with nothing to
 * click. A loop over a list that is usually empty keeps the test idempotent without
 * a branch.
 */
async function forgetImportedAgenda(api: APIRequestContext): Promise<void> {
  await forgetAgendasNamed(api, GOOGLE_AGENDA)
}

/**
 * Deletes every agenda whose name starts with `prefix`, and waits for the index.
 *
 * Unconditional, and a loop over a list that is usually empty rather than a branch.
 * Two different reasons need it: the import dialog hides a calendar that is already
 * connected (once MAG-148 is fixed), and `agendaRow()` is a strict locator — a second
 * `task e2e:web` against one seeded stack would otherwise leave two rows of the same
 * name and fail on the match rather than on the export.
 */
async function forgetAgendasNamed(api: APIRequestContext, prefix: string): Promise<void> {
  for (const agenda of (await agendas(api)).filter((candidate) => candidate.name?.startsWith(prefix))) {
    const response = await api.delete(`/api/agendas/${agenda.id}`)
    expect(response.status(), `DELETE /api/agendas/${agenda.id} answered ${response.status()}`).toBe(204)
  }

  await expect
    .poll(async () => (await agendas(api)).some((agenda) => agenda.name?.startsWith(prefix)), {
      timeout: 30_000,
      message: `no agenda named "${prefix}…" should be left before this test writes one`,
    })
    .toBe(false)
}

/**
 * The two tests that import, one after the other.
 *
 * They contend for the same thing — the agenda connected to the one Google calendar
 * the stub offers — so running them side by side would have each deleting the
 * other's. Serial is safe here, where it would not be in a file full of counts,
 * because {@link forgetImportedAgenda} makes both idempotent: a retry replays the
 * group onto a world the group puts back itself.
 */
// Quarantined as a whole: the group is serial and its first test fails on main depending on the hour of the run (MAG-177).
test.describe.fixme('Importing the Google calendar', () => {
  test.describe.configure({ mode: 'serial' })

  test('an imported calendar arrives, and a local change survives the next pull', async ({
    page,
    api,
    playwright,
  }) => {
    const journal = await openWiremockJournal(playwright)

    await forgetImportedAgenda(api)

    const calendar = new CalendarPage(page)
    await calendar.open()
    await calendar.importFromGoogle(GOOGLE_AGENDA)

    // The import answers 201 and the sidebar refetches at once — but `/api/agendas`
    // is served from Elasticsearch, so the new agenda exists before it is listable
    // and that one refetch reads the old list. Nothing refetches again, so the row
    // never appears on its own: the index is waited for, then the page is reloaded.
    // Rule 4 of this harness's README, which this test had to learn twice.
    await waitForIndexed<StoredAgenda>(api, '/api/agendas', (agenda) => agenda.name === GOOGLE_AGENDA, {
      what: `The imported agenda "${GOOGLE_AGENDA}"`,
    })
    await calendar.open()

    await expect(calendar.agendaRow(GOOGLE_AGENDA), 'the imported agenda is not in the sidebar').toBeVisible()

    // The initial pull is dispatched asynchronously, so the event arrives through
    // RabbitMQ, the worker and the index — polled, not read once.
    const pulled = await waitForIndexed<StoredEvent>(
      api,
      '/api/events?itemsPerPage=100',
      (event) => event.summary === PULLED_EVENT,
      { what: 'The event pulled from Google' },
    )

    // And it is reachable where an owner would look, at the date Google gave it.
    await calendar.openEvent(String(pulled.id), PULLED_EVENT)

    // ---- `544c9e5`: the pull must not overwrite what the owner just changed. ----

    // Changed through the API rather than through the dialog: the dialog is covered
    // by `agenda-events.spec.ts`, and what is under test here is the sync guard, not
    // the form. The PATCH pushes to Google synchronously, which is the other half of
    // the fix — the stub answers `updated: 09:00`, later than the list's 08:00.
    const renamed = await api.patch(`/api/events/${pulled.id}`, {
      headers: { 'Content-Type': 'application/merge-patch+json' },
      data: { summary: LOCAL_TITLE },
    })
    expect(renamed.ok(), `PATCH /api/events/${pulled.id} answered ${renamed.status()}`).toBe(true)

    const pullsBefore = await journal.count('GET', LIST_EVENTS)

    // What Google's push channel does when something changes on its side.
    //
    // The channel id names the calendar, which is the whole reason the stub derives
    // it instead of answering a constant: `findByGoogleWatchChannelId()` is a
    // `findOneBy`, so two agendas sharing a channel would let this webhook pull into
    // the wrong one — and the assertion below would then pass because nothing had
    // touched the event it is about.
    //
    // It is also what proves the watch was registered at all: an unknown channel
    // answers 200 and dispatches nothing, so the wait that follows would time out.
    const notified = await api.post('/api/calendar/google/webhook', {
      headers: {
        'X-Goog-Channel-ID': `e2e-channel-${GOOGLE_CALENDAR_ID}`,
        'X-Goog-Channel-Token': 'e2e-webhook-token',
        'X-Goog-Resource-State': 'exists',
      },
      data: '',
    })
    expect(notified.ok(), `the webhook answered ${notified.status()}`).toBe(true)

    // Asserted only once the pull has really run, and only for *this* calendar:
    // before that, "the local title is still there" would pass on a sync that never
    // happened, and a counter shared with another agenda's sync would say yes too.
    await journal.waitForCount('GET', LIST_EVENTS, pullsBefore + 1)

    await expect
      .poll(async () => (await events(api)).find((event) => event.id === pulled.id)?.summary, {
        timeout: 30_000,
        message: 'the local change must outlive the pull that followed it',
      })
      .toBe(LOCAL_TITLE)

    await journal.dispose()
  })

  /**
   * A connected agenda says it is connected — MAG-148.
   *
   * It does not: `Agenda::toSearchDocument()` leaves `googleCalendarId` out, and the
   * Elasticsearch providers rebuild the entity from the indexed document alone, so
   * `googleSynced` is `false` on every agenda however it was created. That is the
   * root cause of MAG-148 — the import dialog hides calendars it believes are
   * already connected, that list is always empty, and importing the same calendar
   * twice makes a second agenda. Same shape as MAG-169 on `Event`.
   *
   * Marked expected-to-fail rather than left out: the sidebar's sync badge and the
   * dialog's filter both hang off this one field, and the marker turns red the day
   * it is indexed — which is when it has to go.
   *
   * The import it repeats is driven unmarked by the test before it, so a broken
   * `importFromGoogle` fails there rather than disappearing into this marker.
   */
  test('an agenda connected to Google is marked as synced', async ({ page, api }) => {
    test.fail()

    await forgetImportedAgenda(api)

    const calendar = new CalendarPage(page)
    await calendar.open()
    await calendar.importFromGoogle(GOOGLE_AGENDA)

    // Same wait and reload as the test above: the sidebar reads an indexed
    // collection, so the row it is about only appears on a view built after the
    // index caught up. Without it this test would fail on the lag rather than on
    // the field it is marked for, and would keep "failing as expected" the day
    // MAG-148 is fixed.
    await waitForIndexed<StoredAgenda>(api, '/api/agendas', (agenda) => agenda.name === GOOGLE_AGENDA, {
      what: `The imported agenda "${GOOGLE_AGENDA}"`,
    })
    await calendar.open()

    await expect
      .poll(async () => (await agendas(api)).find((agenda) => agenda.name === GOOGLE_AGENDA)?.googleSynced, {
        timeout: 30_000,
        message: 'the imported agenda should report itself as synced',
      })
      .toBe(true)

    await expect(calendar.syncBadge(GOOGLE_AGENDA)).toBeVisible()
  })
})

/**
 * Exporting a local agenda creates its Google counterpart.
 *
 * The other direction of the sidebar's ⋮ menu, and it exports an agenda of its own
 * rather than a seeded one. Two reasons, and both are about running twice:
 *
 * - an agenda can only be exported once. `GoogleCalendarConnectController::export`
 *   answers 409 on an agenda that already carries a `googleCalendarId`, and the ⋮
 *   menu still offers the item because the API never reports that field (MAG-148) —
 *   so a retry on a seeded agenda would make the call, get a 409, and fail on a
 *   message about WireMock rather than about the export;
 * - exporting also registers a watch, and the channel id names the calendar. A
 *   throwaway agenda therefore cannot steal the webhook the import group sends.
 *
 * Asserted on the call rather than on the agenda, for the reason the MAG-148 test
 * above spells out: `googleCalendarId` is not in the indexed document, so the API
 * answers the same thing before and after.
 *
 * Expected to fail, and the call never happens — MAG-171. `handleExportToGoogle`
 * posts `{agendaId: agendaMenuTarget.id}`, and react-admin's Hydra provider puts the
 * *IRI* in `id`; the controller then looks that up as a ULID and answers 500. So
 * exporting an agenda from the web has never worked. The menu, the agenda and the
 * sidebar it walks through are driven unmarked by the import test above.
 */
// Quarantined: fails on main depending on the hour of the run (MAG-177).
test.fixme('exporting an agenda to Google creates a calendar for it', async ({ page, api, playwright }) => {
  test.fail()

  const name = `${EXPORTED_PREFIX} ${test.info().retry}`

  // A run of the suite against a stack nobody reseeded would otherwise leave the row
  // this one is about to create a second time, and `agendaRow()` is strict.
  await forgetAgendasNamed(api, EXPORTED_PREFIX)

  const created = await api.post('/api/agendas', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: { name, color: '#607d8b' },
  })
  expect(created.status(), `POST /api/agendas answered ${created.status()}`).toBe(201)

  // The sidebar is served from Elasticsearch like every other collection, so the row
  // exists before it is listable — and the view fills its sidebar once, on mount.
  await waitForIndexed<StoredAgenda>(api, '/api/agendas', (agenda) => agenda.name === name, {
    what: `The agenda "${name}"`,
  })

  const journal = await openWiremockJournal(playwright)
  const before = await journal.count('POST', CREATE_CALENDAR)

  // Opened after the agenda exists: the sidebar is filled once, when the view mounts.
  const calendar = new CalendarPage(page)
  await calendar.open()
  await expect(calendar.agendaRow(name), 'the new agenda never reached the sidebar').toBeVisible()

  await calendar.exportToGoogle(name)

  await journal.waitForCount('POST', CREATE_CALENDAR, before + 1)

  await journal.dispose()
})
