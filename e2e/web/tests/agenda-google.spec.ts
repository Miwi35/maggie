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
const PULLED_EVENT = 'Réunion importée de Google'
const LOCAL_TITLE = 'Réunion importée (déplacée en salle 2)'

const LIST_EVENTS = /\/google\/calendar\/v3\/calendars\/[^/]+\/events(\?|$)/
const WATCH_EVENTS = /\/google\/calendar\/v3\/calendars\/[^/]+\/events\/watch(\?|$)/
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
  for (const agenda of (await agendas(api)).filter((candidate) => candidate.name === GOOGLE_AGENDA)) {
    const response = await api.delete(`/api/agendas/${agenda.id}`)
    expect(response.status(), `DELETE /api/agendas/${agenda.id} answered ${response.status()}`).toBe(204)
  }

  await expect
    .poll(async () => (await agendas(api)).some((agenda) => agenda.name === GOOGLE_AGENDA), {
      timeout: 30_000,
      message: 'the previously imported agenda should be gone before importing again',
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
test.describe('Importing the Google calendar', () => {
  test.describe.configure({ mode: 'serial' })

  test('an imported calendar arrives, and a local change survives the next pull', async ({
    page,
    api,
    playwright,
  }) => {
    const journal = await openWiremockJournal(playwright)

    await forgetImportedAgenda(api)
    const watchesBefore = await journal.count('POST', WATCH_EVENTS)

    const calendar = new CalendarPage(page)
    await calendar.open()
    await calendar.importFromGoogle(GOOGLE_AGENDA)

    await expect(calendar.agendaRow(GOOGLE_AGENDA), 'the imported agenda is not in the sidebar').toBeVisible()

    // The import really talked to Google: it registered a push channel, which is
    // what gives the webhook below a channel id to arrive on. Asserted through the
    // journal because the API cannot say so — see the MAG-148 test at the bottom.
    await journal.waitForCount('POST', WATCH_EVENTS, watchesBefore + 1)

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

    // What Google's push channel does when something changes on its side. The import
    // registered the watch, so this is the channel the stub handed over.
    const notified = await api.post('/api/calendar/google/webhook', {
      headers: {
        'X-Goog-Channel-ID': 'e2e-channel-id',
        'X-Goog-Channel-Token': 'e2e-webhook-token',
        'X-Goog-Resource-State': 'exists',
      },
      data: '',
    })
    expect(notified.ok(), `the webhook answered ${notified.status()}`).toBe(true)

    // Asserted only once the pull has really run: before that, "the local title is
    // still there" would pass on a sync that never happened.
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
   */
  test('an agenda connected to Google is marked as synced', async ({ page, api }) => {
    test.fail()

    await forgetImportedAgenda(api)

    const calendar = new CalendarPage(page)
    await calendar.open()
    await calendar.importFromGoogle(GOOGLE_AGENDA)

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
 * The other direction of the sidebar's ⋮ menu, on a different agenda from the import
 * on purpose: "Famille" is local in the seed, so this never contends for the row the
 * group above deletes and recreates.
 *
 * Asserted on the call rather than on the agenda, for the reason the MAG-148 test
 * above spells out: `googleCalendarId` is not in the indexed document, so the API
 * answers the same thing before and after.
 */
test('exporting an agenda to Google creates a calendar for it', async ({ page, playwright }) => {
  const journal = await openWiremockJournal(playwright)
  const before = await journal.count('POST', CREATE_CALENDAR)

  const calendar = new CalendarPage(page)
  await calendar.open()
  await calendar.exportToGoogle('Famille')

  await journal.waitForCount('POST', CREATE_CALENDAR, before + 1)

  await journal.dispose()
})
