import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * Nothing of the neighbour's reaches the signed-in user (MAG-114 § 2).
 *
 * MAG-114 found several agenda reads that had forgotten their `WHERE user`, and the
 * reason it took a production session to notice is that a missing filter is
 * invisible with one account: every row belongs to you, so every list looks right.
 * The seed therefore gives the neighbour an agenda, an event at the same hour as the
 * user's lunch, and a task due today — three rows that would surface in the user's
 * own day if a query dropped its filter.
 *
 * Every test here ends on a **pair** of assertions: the neighbour's row is absent
 * for the user *and* present for the neighbour. The second half is what stops the
 * first from passing because the fixture was never loaded, which is the way an
 * isolation test usually lies.
 *
 * The MCP side — `get_upcoming_events`, `get_tasks`, `check_conflicts`,
 * `create_event` — is covered tool by tool in
 * `api/modules/calendar/tests/Mcp/UserIsolationToolsTest.php`. This file holds the
 * promise the owner can see.
 */

const MINE = { event: 'Déjeuner avec Alex', task: 'Appeler le garage' }
const THEIRS = { event: 'Déjeuner du voisin', task: 'Tâche du voisin' }

interface Row {
  summary?: string
  title?: string
}

async function summaries(api: APIRequestContext): Promise<string[]> {
  return (await getCollection<Row>(api, '/api/events?itemsPerPage=100')).map((event) => String(event.summary))
}

async function titles(api: APIRequestContext): Promise<string[]> {
  return (await getCollection<Row>(api, '/api/tasks?itemsPerPage=100')).map((task) => String(task.title))
}

test("the events collection holds the caller's events and no one else's", async ({ api, otherUser }) => {
  const mine = await summaries(api)
  expect(mine).toContain(MINE.event)
  expect(mine).not.toContain(THEIRS.event)

  // The control. Without it a filter that matched nothing at all would pass the
  // assertion above, and so would a seed that never loaded the neighbour's agenda.
  const theirs = await summaries(otherUser.api)
  expect(theirs).toContain(THEIRS.event)
  expect(theirs).not.toContain(MINE.event)
})

test("the tasks collection holds the caller's tasks and no one else's", async ({ api, otherUser }) => {
  const mine = await titles(api)
  expect(mine).toContain(MINE.task)
  expect(mine).not.toContain(THEIRS.task)

  const theirs = await titles(otherUser.api)
  expect(theirs).toContain(THEIRS.task)
  expect(theirs).not.toContain(MINE.task)
})

/**
 * "What do I have today?", asked of the screen that answers it.
 *
 * The dashboard is the one place the owner reads their day whole — events and tasks,
 * today and tomorrow — so it is where a leak would be seen first. It reads four
 * collections, and one of them forgetting its filter is exactly MAG-114.
 */
test("the dashboard shows the owner's day and nothing of the neighbour's", async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  await expect(dashboard.line(MINE.event)).toHaveCount(1)
  await expect(dashboard.line(THEIRS.event), "the neighbour's lunch is on the dashboard").toHaveCount(0)
  await expect(dashboard.line(THEIRS.task), "the neighbour's task is on the dashboard").toHaveCount(0)
})

test("the agenda grid draws the owner's events and nothing of the neighbour's", async ({ page }) => {
  const calendar = new CalendarPage(page)
  await calendar.open()
  // The day view on the anchor's day, where both lunches sit at noon: one chip, and
  // it has to be the owner's.
  await calendar.chooseView('Jour')

  await expect(calendar.chip(MINE.event)).toHaveCount(1)
  await expect(calendar.chip(THEIRS.event), "the neighbour's lunch is on the grid").toHaveCount(0)
})

test("the neighbour's agenda is not in the owner's sidebar", async ({ page }) => {
  const calendar = new CalendarPage(page)
  await calendar.open()

  // Two agendas, both the owner's. The neighbour's is the third row in the database
  // and must not be a row here — `/api/agendas` is served from Elasticsearch, where
  // the filter is a `userId` term rather than a SQL join, so it is its own code path.
  await expect(calendar.agendaRow('Perso')).toBeVisible()
  await expect(calendar.agendaRow('Famille')).toBeVisible()
  await expect(calendar.agendaRow('Agenda du voisin')).toHaveCount(0)
})
