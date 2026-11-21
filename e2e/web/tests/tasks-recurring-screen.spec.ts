import type { APIRequestContext } from '@playwright/test'
import { test, expect, parisDay } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { ROUTES } from '../pages/routes.js'
import { TasksPage } from '../pages/TasksPage.js'

/**
 * A task that repeats, on the admin's screens (MAG-888, extends MAG-100, web side).
 *
 * Given a task « Sortir la poubelle jaune » created through the API on the first Tuesday
 *   after today, with no repetition,
 * When I open its row in Tâches › À venir, « Modifier », « Répéter » → « Toutes les deux
 *   semaines », then « Enregistrer »,
 * Then the API holds rrule FREQ=WEEKLY;INTERVAL=2;BYDAY=TU, its row carries ↻ « Répétée »,
 *   and its sheet reads « Répéter » « Un mardi sur deux »;
 * When I check its row,
 * Then the API has an open task of the same title on that Tuesday + 14 days with the same
 *   rule, the checked one has no rule, and the new row carries ↻.
 */

test.use({ journeyAccount: 'empty' })

const RULE = 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU'
const TASKS = '/api/tasks?itemsPerPage=100'
const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface StoredTask {
  id: string
  title: string
  scheduledDate: string | null
  completedAt: string | null
  rrule: string | null
}

function daysFrom(day: string, offset: number): string {
  const [year, month, date] = day.split('-').map(Number)

  return new Date(Date.UTC(year, month - 1, date + offset)).toISOString().slice(0, 10)
}

async function stored(api: APIRequestContext, id: string): Promise<StoredTask> {
  const response = await api.get(`/api/tasks/${id}`, { headers: { Accept: 'application/ld+json' } })
  expect(response.ok(), `GET /api/tasks/${id} answered ${response.status()}`).toBe(true)

  return (await response.json()) as StoredTask
}

/** The first Tuesday after the Paris day. */
function firstTuesdayAfterToday(): string {
  const today = parisDay()
  const weekday = new Date(`${today}T00:00:00Z`).getUTCDay()

  return daysFrom(today, ((2 - weekday + 7) % 7) || 7)
}

test('a task made to repeat every other Tuesday shows ↻ and its rule, and checking it opens the next one fourteen days later — MAG-888', async ({
  page,
  api,
}) => {
  const { retry } = test.info()
  const title = `Sortir la poubelle jaune${0 === retry ? '' : ` essai ${retry}`}`
  const tuesday = firstTuesdayAfterToday()

  // Given the task, scheduled on that Tuesday, without a rule.
  const created = await api.post('/api/tasks', {
    headers: jsonLd,
    data: { title, priority: 'medium', criticality: 'low', scheduledDate: tuesday },
  })
  expect(created.status(), `POST /api/tasks answered ${created.status()}: ${await created.text()}`).toBe(201)
  const id = ((await created.json()) as { id: string }).id
  await waitForIndexed<StoredTask>(api, TASKS, (t) => id === t.id, { what: `The task "${title}"` })

  // When I open its row, « Modifier », and choose « Toutes les deux semaines »…
  const tasks = new TasksPage(page)
  await tasks.goto(ROUTES.tasksUpcoming)
  await tasks.openTask(title)
  await tasks.sheet.getByRole('button', { name: 'Modifier la tâche' }).click()
  await tasks.repeatField.click()
  await page.getByRole('option', { name: 'Toutes les deux semaines', exact: true }).click()
  await expect(tasks.repeatField).toHaveText('Un mardi sur deux')
  await tasks.form.getByRole('button', { name: 'Enregistrer' }).click()
  await expect(tasks.form).toBeHidden()

  // …the API holds the rule, the row carries ↻ and the sheet reads it.
  await expect.poll(async () => (await stored(api, id)).rrule).toBe(RULE)
  await expect(tasks.repeatMark(tasks.row(title))).toBeVisible()
  await expect(tasks.repeatMark(tasks.row(title))).toHaveAccessibleName('Répétée')
  await tasks.openTask(title)
  await expect(tasks.sheet.getByText('Répéter', { exact: true })).toBeVisible()
  await expect(tasks.sheet.getByText('Un mardi sur deux', { exact: true })).toBeVisible()

  // When I check its row…
  await tasks.checkbox(title).click()

  // …the next one is open fourteen days later with the same rule, and the done one has none.
  const next = await waitForIndexed<StoredTask>(api, TASKS, (t) => title === t.title && null === (t.completedAt ?? null) && id !== t.id, {
    what: `The next "${title}"`,
  })
  expect(next.scheduledDate?.slice(0, 10)).toBe(daysFrom(tuesday, 14))
  expect(next.rrule).toBe(RULE)
  await expect.poll(async () => (await stored(api, id)).rrule).toBeNull()
  expect((await stored(api, id)).completedAt).not.toBeNull()
  await expect(tasks.repeatMark(tasks.row(title))).toBeVisible()
})
