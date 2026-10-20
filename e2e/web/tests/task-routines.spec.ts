import { test, expect, parisDay } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { TasksPage } from '../pages/TasksPage.js'

/**
 * Routines (MAG-511, extends MAG-100): a small thing that comes back — a moment,
 * a rhythm, a tick for today.
 *
 * The stack runs at `E2E_NOW` (CI: a Wednesday at noon in the middle of a month,
 * `e2e/clock.sh`), so « ce mois » holds the tick of the day. The API reads "today"
 * through libfaketime, fixed for the whole run: moving the browser's clock a day
 * would not move the API's, and the routine would still read ticked. « The next
 * day » — untouched tick, count kept — is the API's to prove (RoutineSchedule's
 * PHPUnit tests), not this journey's.
 */

interface StoredRoutine {
  id: string
  title: string
  anchor: string
  recurrence: { kind: string }
  checkedToday: boolean
  monthCount: number
  totalCount: number
}

/** A title the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} (essai ${retry})`
}

async function routines(api: APIRequestContext): Promise<StoredRoutine[]> {
  const response = await api.get('/api/routines', { headers: { Accept: 'application/json' } })
  expect(response.ok(), `GET /api/routines answered ${response.status()}`).toBe(true)

  return ((await response.json()) as { member: StoredRoutine[] }).member
}

/** Deletes every routine called `title`: the journey starts from none. */
async function removeRoutines(api: APIRequestContext, title: string): Promise<void> {
  for (const routine of (await routines(api)).filter((r) => r.title === title)) {
    const response = await api.delete(`/api/routines/${routine.id}`)
    expect(response.status(), `DELETE /api/routines/${routine.id} answered ${response.status()}`).toBe(204)
  }
}

test('a routine created « au réveil, chaque jour » and ticked is done in « Matin », once this month', async ({ page, api }) => {
  const title = perAttempt("Grand verre d'eau")

  // Given no routine of that name…
  await removeRoutines(api, title)

  try {
    const board = new TasksPage(page)
    await board.openRoutines()
    await expect(board.routineCard(title)).toHaveCount(0)

    // When I create it, at waking, every day…
    await board.newRoutine.click()
    await expect(board.routineDialog).toHaveAccessibleName('Nouvelle routine')
    await board.routineDialog.getByRole('textbox', { name: 'Quoi' }).fill(title)
    await board.routineDialog.getByRole('button', { name: 'Au réveil', exact: true }).click()
    await board.routineDialog.getByRole('button', { name: 'Chaque jour', exact: true }).click()
    await board.routineDialog.getByRole('button', { name: 'Enregistrer', exact: true }).click()
    await expect(board.routineDialog).toBeHidden()

    // …then it waits in « Matin », not ticked, at waking.
    const card = board.routineGroup('Matin').getByTestId('routine-card').filter({ has: page.getByText(title, { exact: true }) })
    await expect(card).toBeVisible()
    await expect(card).toContainText('au réveil')
    await expect(board.routineCheck(title)).not.toBeChecked()
    await expect(board.routineCount(title)).toHaveText('ce mois : 0 fois')

    // When I tick it…
    await board.routineCheck(title).click()

    // …then it is done today, once this month — on screen and in the API.
    await expect(board.routineCheck(title)).toBeChecked()
    await expect(board.routineCount(title)).toHaveText('ce mois : 1 fois')
    const [stored] = (await routines(api)).filter((r) => r.title === title)
    expect(stored).toMatchObject({ anchor: 'wake', recurrence: { kind: 'daily' }, checkedToday: true, monthCount: 1, totalCount: 1 })

    // When I come back to the page the same day, the tick and the count are still there.
    await board.openRoutines()
    await expect(board.routineCheck(title)).toBeChecked()
    await expect(board.routineCount(title)).toHaveText('ce mois : 1 fois')

    // When I untick it, the day is back to not done and the month to none — the API agrees.
    await board.routineCheck(title).click()
    await expect(board.routineCheck(title)).not.toBeChecked()
    await expect(board.routineCount(title)).toHaveText('ce mois : 0 fois')
    const check = await api.get(`/api/routines/day/${parisDay()}`, { headers: { Accept: 'application/json' } })
    expect(check.ok(), `GET /api/routines/day/${parisDay()} answered ${check.status()}`).toBe(true)
    const day = (await check.json()) as { moments: { routines: { title: string; checked: boolean }[] }[] }
    expect(day.moments.flatMap((m) => m.routines).find((r) => r.title === title)).toMatchObject({ checked: false })
  } finally {
    await removeRoutines(api, title)
  }
})
