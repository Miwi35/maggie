import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { openWiremockJournal } from '../helpers/wiremock.js'
import { GoogleSettingsPage } from '../pages/GoogleSettingsPage.js'

/**
 * Choosing the Google Tasks list to sync with (MAG-118).
 *
 * The settings screen called `/calendar/google/task-lists`, `connect-tasks` and
 * `disconnect-tasks` from the day it was written; none of the three existed, so
 * the select was always empty and "Connecter" posted into the void. Meanwhile
 * the API synced whichever list Google returned first — an account with several
 * lists silently synced one of them.
 *
 * WireMock answers **two** lists on purpose (see
 * `.docker/e2e/wiremock/README.md`): with one, "the chosen list" and "the first
 * list" are the same string and the journey would hold nothing. The second one,
 * "Courses Google", is not the one the seed starts connected to.
 *
 * What is asserted is the whole round trip, screen to Google: the owner picks a
 * list, the API stores it, and the pull that follows asks Google for *that*
 * list — read from WireMock's journal, which is the only witness to it (the
 * pull is asynchronous and leaves no trace the API exposes).
 *
 * Serial, and it puts the seed's list back: the choice is one field on the one
 * seeded user, so two tests changing it side by side would each read the
 * other's, and a journey leaving the account on "Courses Google" would move
 * what the next run starts from.
 */

const SEEDED_LIST = { id: 'e2e-task-list', title: 'Mes tâches' }
const OTHER_LIST = { id: 'e2e-task-list-courses', title: 'Courses Google' }

/** Scoped to the list it is about: the journal is global, and so is the other list's. */
const listTasks = (taskListId: string) => new RegExp(`/google/tasks/v1/lists/${taskListId}/tasks(\\?|$)`)

async function connectedTaskListId(api: APIRequestContext): Promise<string | null> {
  const response = await api.get('/api/users/me', { headers: { Accept: 'application/ld+json' } })
  expect(response.ok(), `GET /api/users/me answered ${response.status()}`).toBe(true)

  return ((await response.json()) as { googleTaskListId?: string | null }).googleTaskListId ?? null
}

/** Back to what the seed sets up, whichever way the test before it ended. */
async function connect(api: APIRequestContext, googleTaskListId: string): Promise<void> {
  const response = await api.post('/api/calendar/google/connect-tasks', {
    headers: { 'Content-Type': 'application/json' },
    data: { googleTaskListId },
  })
  expect(response.ok(), `POST connect-tasks answered ${response.status()}`).toBe(true)
}

async function disconnect(api: APIRequestContext): Promise<void> {
  const response = await api.post('/api/calendar/google/disconnect-tasks', {
    headers: { 'Content-Type': 'application/json' },
    data: '',
  })
  expect(response.ok(), `POST disconnect-tasks answered ${response.status()}`).toBe(true)
}

test.describe('The Google Tasks list', () => {
  test.describe.configure({ mode: 'serial' })

  test.afterEach(async ({ api }) => {
    await connect(api, SEEDED_LIST.id)
  })

  test('the screen states the list already chosen, and lets it go', async ({ page, api }) => {
    await connect(api, SEEDED_LIST.id)

    const settings = new GoogleSettingsPage(page)
    await settings.open()

    await expect(settings.syncedWith(SEEDED_LIST.title), 'the connected list should be named').toBeVisible()
    await expect(settings.taskListSelect, 'a connected screen has nothing to choose').toHaveCount(0)

    await settings.disconnectButton.click()

    await expect
      .poll(() => connectedTaskListId(api), { message: 'disconnecting should clear the stored list' })
      .toBe(null)
    await expect(settings.taskListSelect, 'the two lists should be offered again').toBeVisible()
  })

  /**
   * The choice reaches Google — the regression this ticket is about.
   *
   * The journal is what makes it mean something: the pull is dispatched to
   * RabbitMQ and handled by the worker, and asserting only the stored field
   * would pass on a sync that still read the account's first list.
   */
  test('the chosen list is the one the sync reads', async ({ page, api, playwright }) => {
    await disconnect(api)

    const journal = await openWiremockJournal(playwright)
    const before = await journal.count('GET', listTasks(OTHER_LIST.id))

    const settings = new GoogleSettingsPage(page)
    await settings.open()

    await expect(settings.taskListSelect).toBeVisible()
    await expect(settings.connectButton, 'nothing may be connected before a list is picked').toBeDisabled()

    await settings.connectTaskList(OTHER_LIST.title)

    await expect(settings.syncedWith(OTHER_LIST.title)).toBeVisible()
    await expect
      .poll(() => connectedTaskListId(api), { message: 'the chosen list should be stored on the user' })
      .toBe(OTHER_LIST.id)

    await journal.waitForCount('GET', listTasks(OTHER_LIST.id), before + 1)

    await journal.dispose()
  })
})
