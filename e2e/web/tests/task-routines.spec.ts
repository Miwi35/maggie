import { test, expect, parisDay, parisTime } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { TasksPage } from '../pages/TasksPage.js'

/**
 * Routines (MAG-511, MAG-512, extends MAG-100): a small thing that comes back — a moment,
 * a rhythm, a tick for today — and the ones of the moment above the day (on the home: the
 * Accueil's Tâches card, MAG-561).
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

interface DayOfRoutines {
  moments: { routines: { title: string; checked: boolean }[] }[]
}

/** What is due today, and what of it is ticked: as the API counts them. */
async function dayCounts(api: APIRequestContext): Promise<{ done: number; due: number }> {
  const response = await api.get(`/api/routines/day/${parisDay()}`, { headers: { Accept: 'application/json' } })
  expect(response.ok(), `GET /api/routines/day/${parisDay()} answered ${response.status()}`).toBe(true)
  const due = ((await response.json()) as DayOfRoutines).moments.flatMap((moment) => moment.routines)

  return { done: due.filter((routine) => routine.checked).length, due: due.length }
}

test('the routines of the morning ride above the day; a tick there is ticked in the « Routines » tab too, and the day is untouched', async ({ page, api }) => {
  const title = perAttempt("Grand verre d'eau du matin")
  await removeRoutines(api, title)

  try {
    // Given a routine at waking, every day, and the clock at 07:45…
    const created = await api.post('/api/routines', {
      headers: { Accept: 'application/json' },
      data: { title, anchor: 'wake', recurrence: { kind: 'daily' } },
    })
    expect(created.status(), `POST /api/routines answered ${created.status()}`).toBe(201)
    await page.context().clock.setFixedTime(new Date(parisTime(parisDay(), '07:45:00')))
    const before = await dayCounts(api)

    // When I open « Aujourd'hui »…
    const board = new TasksPage(page)
    await board.open()

    // …then the band « Routines · matin » holds it, not ticked.
    const band = board.content.getByRole('region', { name: 'Routines · matin' })
    const pill = band.getByRole('button', { name: title, exact: true })
    await expect(pill).toHaveAttribute('aria-pressed', 'false')
    // (the ring is absent while the day holds no task)
    const ring = (await board.ring.count()) > 0 ? await board.ring.getAttribute('aria-label') : null
    const hadNow = await board.now.count()

    // When I tick it…
    await pill.click()

    // …then the pill is checked — and the ring and « Maintenant » have not moved.
    await expect(pill).toHaveAttribute('aria-pressed', 'true')
    if (null === ring) await expect(board.ring).toHaveCount(0)
    else await expect(board.ring).toHaveAttribute('aria-label', ring)
    await expect(board.now).toHaveCount(hadNow)
    await expect.poll(() => dayCounts(api)).toEqual({ done: before.done + 1, due: before.due })

    // When I open « Routines » from the page, the routine reads ticked there too.
    await board.select('Routines')
    await expect(board.routineCheck(title)).toBeChecked()
  } finally {
    await removeRoutines(api, title)
  }
})
