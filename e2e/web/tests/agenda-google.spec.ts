import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { openWiremockJournal } from '../helpers/wiremock.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * Google Calendar, simulated (MAG-100).
 *
 * Out of MAG-177's quarantine (#44, #45), and on evidence rather than on a rerun:
 * this file was skipped as hour-dependent, and it was not. Its import test never
 * reloaded — the trace showed one `GET /admin/` for the whole test, so the sidebar
 * was asserted against the list fetched before the import, and the hour never came
 * into it (see `AdminShell.goto`). Its sync-badge test then failed on a locator
 * that cannot work here at all: MUI writes `data-testid` on an icon only when
 * `NODE_ENV !== 'production'`, and the stack serves a built bundle. Both are fixed,
 * so the file asserts again — MAG-148's own journey lives in it, and a skipped
 * journey is a journey that holds nothing.
 *
 * The export test keeps its own marker: MAG-171 is unfixed, and that is a product
 * bug rather than a flake.
 *
 * Every Google call the stack makes goes to WireMock
 * (`GOOGLE_API_BASE_URL=http://wiremock:8080/google/`), so this journey drives the
 * real client, the real sync service and the real webhook — only the far side is a
 * fixture. A request leaving the network would be a bug rather than a dependency,
 * and the journal is also how an effect with no other trace is asserted — the
 * export below has none.
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

/**
 * The name the imported agenda carries, in Maggie and in the import dialog alike.
 *
 * Not the stub's `summary`: the one calendar it offers is the account's primary
 * one, and Maggie calls that "Défaut" rather than whatever label Google hangs on
 * the account — an address, or the account holder's name, which is what MAG-148
 * reported seeing. The mapping itself is unit-tested (`GoogleCalendarNameMapper`);
 * what this journey holds is that both ends agree on the name.
 */
const GOOGLE_AGENDA = 'Défaut'
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
 * Unconditional, and not housekeeping for its own sake: the import dialog hides a
 * calendar that is already connected, so a retry would open it on "Tous les
 * calendriers Google sont déjà importés" with nothing to click. A loop over a list
 * that is usually empty keeps the test idempotent without a branch.
 */
async function forgetImportedAgenda(api: APIRequestContext): Promise<void> {
  await forgetAgendasNamed(api, GOOGLE_AGENDA)
}

/**
 * Deletes every agenda whose name starts with `prefix`, and waits for the index.
 *
 * Unconditional, and a loop over a list that is usually empty rather than a branch.
 * Two different reasons need it: the import dialog hides a calendar that is already
 * connected, and `agendaRow()` is a strict locator — a second `task e2e:web` against
 * one seeded stack would otherwise leave two rows of the same name and fail on the
 * match rather than on the export.
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
test.describe('Importing the Google calendar', () => {
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
    // Rule 4 of this harness's README, which this test had to learn twice — and
    // `open()` reloads rather than re-`goto`ing, which is the third time: under a
    // hash router, navigating to the URL already shown remounts nothing.
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
   * A connected agenda says it is connected — the root cause of MAG-148.
   *
   * It used not to: `Agenda::toSearchDocument()` left `googleCalendarId` out, and
   * the Elasticsearch providers rebuild the entity from the indexed document alone,
   * so `googleSynced` came back `false` on every agenda however it was created. The
   * import dialog hides calendars it believes are already connected, that list was
   * therefore always empty, and importing the same calendar twice made a second
   * agenda — which is how production ended up with two "Concerts" syncing against
   * each other. Same shape as MAG-169 on `Event`.
   *
   * The field is indexed now, and both things that hang off it are asserted here:
   * the API's own answer, and the sidebar's sync badge.
   *
   * The import it repeats is driven by the test before it, so a broken
   * `importFromGoogle` fails there first.
   */
  test('an agenda connected to Google is marked as synced', async ({ page, api }) => {
    await forgetImportedAgenda(api)

    const calendar = new CalendarPage(page)
    await calendar.open()
    await calendar.importFromGoogle(GOOGLE_AGENDA)

    // Same wait and reload as the test above: the sidebar reads an indexed
    // collection, so the row it is about only appears on a view built after the
    // index caught up. Without it this test would fail on the lag rather than on
    // the field it is about.
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

  /**
   * Connecting the same calendar twice leaves one agenda — MAG-148.
   *
   * Production held two "Concerts" agendas on one Google calendar, each syncing on
   * its own and duplicating every event. Two guards now, and both are asserted:
   * the dialog no longer offers a calendar it already holds, and the endpoint
   * behind it answers the first agenda instead of making a second one — the dialog
   * is a convenience, the endpoint is the guarantee (and the one the mobile app
   * and Maggie herself go through).
   *
   * The second connection goes through the API because the UI is, by then,
   * refusing to make it at all.
   */
  test('connecting the same Google calendar twice leaves a single agenda', async ({ page, api }) => {
    await forgetImportedAgenda(api)

    const calendar = new CalendarPage(page)
    await calendar.open()
    await calendar.importFromGoogle(GOOGLE_AGENDA)

    const imported = await waitForIndexed<StoredAgenda>(
      api,
      '/api/agendas',
      (agenda) => agenda.googleCalendarId === GOOGLE_CALENDAR_ID,
      { what: `The imported agenda "${GOOGLE_AGENDA}"` },
    )

    // What the owner sees on a second try: nothing left to import.
    await calendar.open()
    const dialog = await calendar.openImportDialog()
    await expect(
      dialog.getByText('Tous les calendriers Google sont déjà importés'),
      'the dialog still offered a calendar that is already connected',
    ).toBeVisible()
    await dialog.getByRole('button', { name: 'Fermer' }).click()

    // And the endpoint the dialog would have called, asked directly.
    const again = await api.post('/api/calendar/google/import', {
      headers: { 'Content-Type': 'application/json' },
      data: { googleCalendarId: GOOGLE_CALENDAR_ID },
    })
    expect(again.status(), 'reconnecting is a success, not a conflict').toBe(200)
    expect(
      ((await again.json()) as { id: string }).id,
      'the second connection should answer the agenda the first one made',
    ).toBe(imported.id)

    await expect
      .poll(async () => (await agendas(api)).filter((agenda) => agenda.googleCalendarId === GOOGLE_CALENDAR_ID).length, {
        timeout: 30_000,
        message: 'the agenda list should show the Google calendar once',
      })
      .toBe(1)
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
 *   menu hides the item for such an agenda — so a retry on a seeded agenda would
 *   fail on a missing menu item rather than on the export;
 * - exporting also registers a watch, and the channel id names the calendar. A
 *   throwaway agenda therefore cannot steal the webhook the import group sends.
 *
 * Asserted on the call rather than on the agenda: what the export owes Google is
 * the calendar it creates there, and the stub is the only witness to it.
 *
 * It found MAG-171: `handleExportToGoogle` posted the agenda's IRI where the controller
 * looks up a ULID, and got a 500. It now posts the bare identifier.
 */
// Quarantined: fails on main depending on the hour of the run (MAG-177).
test.fixme('exporting an agenda to Google creates a calendar for it', async ({ page, api, playwright }) => {
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
