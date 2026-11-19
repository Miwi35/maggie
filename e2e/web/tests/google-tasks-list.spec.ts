import { test, expect, JOURNEY_HEADER } from '../fixtures/index.js'
import { signIn } from '../fixtures/session.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection } from '../helpers/api.js'
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
 * Each test has an account of its own: a pull only deletes the tasks of the
 * account it runs for, so the shared account's tasks are never touched.
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

/** The one task `mappings/google.json` puts in every list it is asked for. */
const PULLED_TASK = 'Tâche importée de Google'

test.use({ journeyAccount: 'empty' })

test.describe('The Google Tasks list', () => {
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

  /**
   * Switching the list pulls Google's, and the stub answers one fixed task: a
   * pull deletes every local task of the account whose Google id is not in that
   * answer. Run on the shared account it took away what other journeys had just
   * created (chat.spec.ts, MAG-873), so the pull must stay on the journey's own.
   */
  test("the pull of a journey account leaves the shared account's tasks alone", async ({
    api,
    baseURL,
    playwright,
    journey,
  }) => {
    const shared = await signIn(baseURL as string, 'e2e@maggie.local', { [JOURNEY_HEADER]: journey })
    const sharedApi = await playwright.request.newContext({
      baseURL: baseURL as string,
      extraHTTPHeaders: { Authorization: `Bearer ${shared.token}`, [JOURNEY_HEADER]: journey },
    })
    const journal = await openWiremockJournal(playwright)
    const pushed = /\/lists\/e2e-task-list\/tasks(\?|$)/

    try {
      const pushes = await journal.count('POST', pushed)
      const created = await sharedApi.post('/api/tasks', {
        headers: { 'Content-Type': 'application/json' },
        data: { title: 'Survivre au changement de liste' },
      })
      expect(created.ok(), `POST /api/tasks answered ${created.status()}`).toBe(true)
      const { id } = (await created.json()) as { id: string }
      await journal.waitForCount('POST', pushed, pushes + 1)

      await disconnect(api)
      const pulls = await journal.count('GET', listTasks(SEEDED_LIST.id))
      await connect(api, SEEDED_LIST.id)
      await journal.waitForCount('GET', listTasks(SEEDED_LIST.id), pulls + 1)
      await expect
        .poll(
          async () =>
            (await getCollection<{ title?: string }>(api, '/api/tasks?itemsPerPage=100')).some(
              (task) => task.title === PULLED_TASK,
            ),
          { message: 'the pull should have written the Google task for the journey account' },
        )
        .toBe(true)

      const survivor = await sharedApi.get(`/api/tasks/${id}`)
      expect(survivor.status(), "the shared account's task should survive another account's pull").toBe(200)

      await sharedApi.delete(`/api/tasks/${id}`)
    } finally {
      await journal.dispose()
      await sharedApi.dispose()
    }
  })
})
