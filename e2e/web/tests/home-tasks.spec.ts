import type { APIRequestContext } from '@playwright/test'
import { test, expect, parisDay } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'
import { TasksPage } from '../pages/TasksPage.js'

/**
 * The Tâches card of the Accueil (MAG-561, extends MAG-99): the day's tasks, ticked in place.
 *
 * Given three tasks of the day, when I tick the first one from the home, then the card shows
 * « 1 sur 3 » and the task is done in « Tâches ».
 *
 * On an account of its own (`e2e-home-tasks@maggie.local`, `api/fixtures/e2e/64-home-tasks.yaml`): the
 * ring counts the day's tasks exactly, and the shared account holds those of every other journey. The
 * journey writes its three tasks and removes them after, so a retry starts from the same day.
 */

const TITLES = ['Appeler le garagiste', 'Renvoyer le formulaire', 'Arroser le basilic'] as const

interface StoredTask {
  id: string
  title: string
  completedAt: string | null
}

async function storedTasks(api: APIRequestContext): Promise<StoredTask[]> {
  const response = await api.get('/api/tasks', { headers: { Accept: 'application/ld+json' }, params: { itemsPerPage: '100' } })
  expect(response.ok(), `GET /api/tasks answered ${response.status()}`).toBe(true)

  return ((await response.json()) as { member: StoredTask[] }).member
}

test.describe('the Tâches card of the Accueil', () => {
  // One account, so one test at a time: the ring counts the day's tasks exactly.
  test.describe.configure({ mode: 'serial' })

  // Deleted by id, not read back from the collection: the collection is served from an index that lags.
  let created: string[] = []

  test.beforeEach(async ({ homeTasksUser }) => {
    created = []

    // Written in order: the first one is first.
    for (const title of TITLES) {
      const response = await homeTasksUser.api.post('/api/tasks', {
        headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
        data: { title, dueDate: parisDay() },
      })
      expect(response.ok(), `POST /api/tasks answered ${response.status()}`).toBe(true)
      created.push(String(((await response.json()) as { id: string }).id))
    }
  })

  test.afterEach(async ({ homeTasksUser }) => {
    for (const id of created) {
      const response = await homeTasksUser.api.delete(`/api/tasks/${id}`)
      expect([204, 404], `DELETE /api/tasks/${id} answered ${response.status()}`).toContain(response.status())
    }
  })

  test('ticking the first of three tasks of the day shows « 1 sur 3 » and the task is done in « Tâches »', async ({ homeTasksUser }) => {
    const { page, api } = homeTasksUser
    const home = new HomePage(page)

    // Given three tasks of the day, none done…
    await home.open()
    await expect(home.tasksRing).toHaveText('0 sur 3')
    for (const title of TITLES) {
      await expect(home.tick(title)).not.toBeChecked()
    }

    // When I tick the first one from the home…
    const first = TITLES[0]
    await home.tick(first).click()

    // …then the card shows « 1 sur 3 », without a reload…
    await expect(home.tasksRing).toHaveText('1 sur 3')
    await expect(home.tick(first)).toBeChecked()
    await expect(home.tick(TITLES[1])).not.toBeChecked()

    // …the API holds it done…
    await expect
      .poll(async () => (await storedTasks(api)).find((task) => task.title === first)?.completedAt != null, {
        timeout: 30_000,
        message: 'the task ticked from the home should be done in the API',
      })
      .toBe(true)

    // …and it is among the done tasks of « Tâches ».
    const board = new TasksPage(page)
    await board.open()
    await board.select('Faites')
    await expect(board.row(first)).toHaveCount(1)
    await expect(board.checkbox(first)).toBeChecked()
  })

  test('« Demain » shows tomorrow\'s card with nothing to tick', async ({ homeTasksUser }) => {
    const home = new HomePage(homeTasksUser.page)

    await home.open('tomorrow')

    // Today's tasks are not tomorrow's, and tomorrow has no ring to fill.
    await expect(home.tick(TITLES[0])).toHaveCount(0)
    await expect(home.tasksRing).toHaveCount(0)
  })
})
