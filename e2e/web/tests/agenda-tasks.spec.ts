import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * Tasks, from the agenda and from the dashboard (MAG-100).
 *
 * The regression this file is built around is `33f0506`: the `ExistsFilter` on
 * `Task` only declared `doneDate`, so `exists[dueDate]=false` was accepted and
 * silently ignored, and the dashboard's "undated tasks" query came back with every
 * undone task — dated ones leaking into "Aujourd'hui". A parameter the API does not
 * declare is dropped without a word, which is why the first test asks the API
 * directly rather than counting lines on a screen that was patched to filter again
 * on its own side.
 *
 * `b576cc6` and `751124a` renamed `name` → `title` and `doneDate` → `completedAt`
 * and had to be carried to the mobile app by hand; `api/contract/` holds that side
 * now (MAG-104), so nothing here repeats it.
 */

interface StoredTask {
  id?: string
  title?: string
  dueDate?: string | null
  completedAt?: string | null
}

const DAY = seedDate()

async function tasks(api: APIRequestContext, query = ''): Promise<StoredTask[]> {
  return getCollection<StoredTask>(api, `/api/tasks?itemsPerPage=100${query}`)
}

/** A title the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} (essai ${retry})`
}

/**
 * The undated filter answers the question it was asked — `33f0506`.
 *
 * Both directions, because one alone proves nothing: a filter that is ignored
 * returns the undated task too, and a filter that matched nothing would return an
 * empty list and look just as correct.
 */
test('exists[dueDate]=false returns the undated task and nothing dated', async ({ api }) => {
  const undated = await tasks(api, '&exists%5BdueDate%5D=false')
  const titles = undated.map((task) => task.title)

  expect(titles).toContain('Trier les photos de vacances')
  expect(titles).not.toContain('Appeler le garage')
  expect(
    undated.filter((task) => task.dueDate),
    'a task with a due date came back from the undated filter',
  ).toEqual([])
})

test('a task created from the agenda lands in the database and on the grid', async ({ page, api }) => {
  const title = perAttempt('Déposer le colis')
  const calendar = new CalendarPage(page)
  await calendar.open()

  await calendar.createTask({ title, dueDate: DAY, criticality: 'Haute' })

  const stored = await waitForIndexed<StoredTask>(api, '/api/tasks?itemsPerPage=100', (task) => task.title === title, {
    what: `The task "${title}"`,
  })
  expect(stored.completedAt ?? null).toBeNull()

  // Tasks are drawn as all-day chips. The day view, where the all-day row holds
  // only what is due that day — month view folds a busy day behind "+N autres".
  await calendar.chooseView('Jour')
  await expect(calendar.chip(title)).toHaveCount(1)
})

test('ticking a task off on the dashboard completes it and takes it out of the day', async ({ page, api }) => {
  const title = perAttempt('Rappeler le plombier')
  const calendar = new CalendarPage(page)
  await calendar.open()
  await calendar.createTask({ title, dueDate: DAY })

  const stored = await waitForIndexed<StoredTask>(api, '/api/tasks?itemsPerPage=100', (task) => task.title === title, {
    what: `The task "${title}"`,
  })

  const dashboard = new DashboardPage(page)
  await dashboard.open()
  // Due today, so it belongs to "Aujourd'hui" — the one bucket that also gathers
  // what is overdue and what has no date at all.
  await expect(dashboard.line(title)).toHaveCount(1)

  await dashboard.taskCheckbox(title).click()

  // The data first: the widget redraws from Elasticsearch, so a line that is still
  // there a moment later says nothing until the write is known to have landed.
  await expect
    .poll(async () => (await tasks(api)).find((task) => task.id === stored.id)?.completedAt != null, {
      timeout: 30_000,
      message: 'the task should be stored as completed',
    })
    .toBe(true)

  await dashboard.open()
  await expect(dashboard.line(title), 'a completed task is still in the day').toHaveCount(0)
})

/**
 * A finished task can be reopened — `MAG-112`, and the reason the seed ships one.
 *
 * From the calendar rather than from the dashboard, and that is not a detour: the
 * digest only ever reads undone tasks, so a completed one has no checkbox there to
 * untick. The agenda draws it with a ✓ and its pencil opens the form that holds the
 * "Terminée" switch, which is the only way back.
 */
test('a finished task can be reopened from the agenda', async ({ page, api }) => {
  const title = 'Sortir les poubelles'
  const taskId = seedId('e2e_task_done')

  // Put the seeded task back to "done" before anything else. It is the one test
  // here that changes a fixture rather than its own row, so a retry would
  // otherwise start on the state the first attempt left — with nothing to reopen
  // and no ✓ on the grid. Done unconditionally, which is what keeps it free of the
  // branch an "if it is already done" check would need.
  await completeTask(api, taskId)

  const calendar = new CalendarPage(page)
  await calendar.open()

  // Due the day before the anchor, so one step back in the day view always lands
  // on it — where month view would have it in the previous month half the time.
  await calendar.chooseView('Jour')
  await calendar.goBack()

  await calendar.chip(`✓ ${title}`).click()
  await expect(calendar.popover).toBeVisible()
  await calendar.editFromPopover()

  const dialog = calendar.editTaskDialog
  await expect(dialog).toBeVisible()
  await dialog.getByRole('switch', { name: 'Terminée' }).uncheck()
  await dialog.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(dialog).toBeHidden()

  await expect
    .poll(async () => (await tasks(api)).find((task) => task.id === taskId)?.completedAt == null, {
      timeout: 30_000,
      message: 'the reopened task should have no completion date left',
    })
    .toBe(true)
})

/** Marks a task done through the API and waits for the grid's index to agree. */
async function completeTask(api: APIRequestContext, taskId: string): Promise<void> {
  const response = await api.patch(`/api/tasks/${taskId}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { completedAt: '2026-01-01T20:15:00+01:00' },
  })
  expect(response.ok(), `PATCH /api/tasks/${taskId} answered ${response.status()}`).toBe(true)

  await expect
    .poll(async () => (await tasks(api)).find((task) => task.id === taskId)?.completedAt != null, {
      timeout: 30_000,
      message: 'the task should be findable as completed before the grid is read',
    })
    .toBe(true)
}
