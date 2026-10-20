import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { assistantText, calledTools, toolResults } from '../helpers/agui.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { TasksPage } from '../pages/TasksPage.js'

/**
 * Routines by conversation (MAG-513, extends MAG-99): Maggie reads them, creates
 * them and ticks them, through the real `list_routines`, `save_routine` and
 * `check_routine` — only the model is scripted.
 *
 * What the journey asserts is the data and the screen, never her wording beyond
 * the scripted « C'est noté ». The tick is made on the id her own `list_routines`
 * returned (the scenario captures it from the tool result, `55-routine-check.yaml`):
 * a wrong id would be a refused call, not a tick.
 *
 * ## Why the stock account, and `retries: 0`
 *
 * « Grand verre d'eau » is also the title `task-routines.spec.ts` creates on the
 * owner's account, and the scenarios match these titles by name: two routines of
 * one name would make the tick ambiguous. The stock account owns nothing routine
 * related, and a retry would start on what the first attempt left — so each test
 * clears its titles before and after.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const WATER = "Grand verre d'eau"
const BINS = 'Sortir les poubelles'

interface StoredRoutine {
  id: string
  title: string
  anchor: string
  recurrence: { kind: string; weekday?: number }
  checkedToday: boolean
  monthCount: number
  totalCount: number
}

const JSON_ONLY = { Accept: 'application/json' }

async function routines(api: APIRequestContext): Promise<StoredRoutine[]> {
  const response = await api.get('/api/routines', { headers: JSON_ONLY })
  expect(response.ok(), `GET /api/routines answered ${response.status()}`).toBe(true)

  return ((await response.json()) as { member: StoredRoutine[] }).member
}

async function removeRoutines(api: APIRequestContext, ...titles: string[]): Promise<void> {
  for (const routine of (await routines(api)).filter((r) => titles.includes(r.title))) {
    const response = await api.delete(`/api/routines/${routine.id}`)
    expect(response.status(), `DELETE /api/routines/${routine.id} answered ${response.status()}`).toBe(204)
  }
}

test.beforeEach(async ({ stockUser }) => {
  await removeRoutines(stockUser.api, WATER, BINS)
})

test.afterEach(async ({ stockUser }) => {
  await removeRoutines(stockUser.api, WATER, BINS)
})

test('« j\'ai bu mon grand verre d\'eau » ticks the routine and the Routines tab shows it done', async ({
  stockUser,
}) => {
  const { api, page } = stockUser

  // Given « Grand verre d'eau », at waking, every day, not ticked.
  const created = await api.post('/api/routines', {
    headers: JSON_ONLY,
    data: { title: WATER, anchor: 'wake', recurrence: { kind: 'daily' } },
  })
  expect(created.status(), `the API refused the routine: ${await created.text()}`).toBe(201)

  const board = new TasksPage(page)
  await board.openRoutines()
  await expect(board.routineCheck(WATER)).not.toBeChecked()

  // When I tell Maggie…
  const chat = new ChatPanel(page)
  const events = await chat.send("J'ai bu mon grand verre d'eau")

  // …she reads the day's routines, then ticks that one — and both calls went through.
  expect(calledTools(events)).toEqual(['list_routines', 'check_routine'])
  expect(toolResults(events)).toEqual([
    expect.objectContaining({ toolName: 'list_routines', status: 'success' }),
    expect.objectContaining({ toolName: 'check_routine', status: 'success' }),
  ])
  expect(assistantText(events)).toContain("C'est noté")

  // Then the tick is stored, once…
  await expect
    .poll(async () => (await routines(api)).find((r) => WATER === r.title), {
      message: 'the tick never reached the routine',
      timeout: 30_000,
    })
    .toMatchObject({ checkedToday: true, monthCount: 1, totalCount: 1 })

  // …and the Routines tab shows it done.
  await board.openRoutines()
  await expect(board.routineCheck(WATER)).toBeChecked()
  await expect(board.routineCount(WATER)).toHaveText('ce mois : 1 fois')
})

test('« ajoute une routine : sortir les poubelles le mardi après le dîner » shows it under « Chaque semaine »', async ({
  stockUser,
}) => {
  const { api, page } = stockUser

  // Given no routine « Sortir les poubelles ».
  expect(await routines(api)).toEqual(expect.not.arrayContaining([expect.objectContaining({ title: BINS })]))

  const board = new TasksPage(page)
  await board.openRoutines()
  await expect(board.routineCard(BINS)).toHaveCount(0)

  // When I ask Maggie to add it…
  const chat = new ChatPanel(page)
  const events = await chat.send('Ajoute une routine : sortir les poubelles le mardi après le dîner')

  expect(calledTools(events)).toEqual(['save_routine'])
  expect(toolResults(events)).toEqual([expect.objectContaining({ toolName: 'save_routine', status: 'success' })])
  expect(assistantText(events)).toContain('Routine ajoutée')

  // …then it is stored once, on Tuesday after dinner — « le mardi » is weekday 2…
  await expect
    .poll(async () => (await routines(api)).filter((r) => BINS === r.title), {
      message: 'the routine never reached the API',
      timeout: 30_000,
    })
    .toHaveLength(1)
  const [stored] = (await routines(api)).filter((r) => BINS === r.title)
  expect(stored).toMatchObject({ anchor: 'afterDinner', recurrence: { kind: 'weekly', weekday: 2 }, totalCount: 0 })

  // …and the Routines tab files it under « Chaque semaine ».
  await board.openRoutines()
  await expect(board.routineGroup('Chaque semaine').getByText(BINS, { exact: true })).toBeVisible()
})
