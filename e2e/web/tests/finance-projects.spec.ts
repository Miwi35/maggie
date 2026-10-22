import type { APIRequestContext, Locator, Page } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import { FinanceProjectsPage } from '../pages/FinanceProjectsPage.js'

/**
 * The projects in priority order (MAG-519, extends MAG-102).
 *
 * Seeded: « Déménagement » first (a locked date, 1 March 2027), then « Canapé »,
 * with no date, which waits for it. The journey moves « Canapé » to the top
 * with the mouse, checks the order outlives a reload, puts it back with the
 * keyboard, then gives it a deadline before the move — a dependency the page
 * must flag.
 *
 * The planning over 36 months (MAG-526): Maggie reads it (« Quand pourrai-je acheter
 * le canapé ? ») and the API gives it. Canapé comes after Déménagement; moving it
 * first changes the next reading, and it still never comes before the move it
 * waits for. The seeded saving capacity is not asserted (it follows the seeded
 * spending): what holds whatever it is, is the order and the dependency.
 *
 * The Gantt under the list (MAG-527) draws that same planning: the sofa's purchase after the
 * move's, and dragging the sofa to the top makes its bar come first once the planning is read again.
 *
 * Only this file reads or writes the projects; `finally` puts the seed back,
 * so a retry starts where the first attempt did.
 */

// The tests write the same two seeded projects: one after the other.
test.describe.configure({ mode: 'serial' })

const MOVE = 'Déménagement'
const SOFA = 'Canapé'
const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }

interface StoredProject {
  id: string
  name: string
  position: number
  dateMode: string
  targetOn: string | null
}

const projects = async (api: APIRequestContext): Promise<StoredProject[]> =>
  (await getCollection<StoredProject>(api, '/api/finance/projects?itemsPerPage=100')).sort((a, b) => a.position - b.position)

const named = (list: StoredProject[], name: string): StoredProject => {
  const found = list.find((project) => project.name === name)
  if (!found) throw new Error(`the seeded project « ${name} » is missing`)

  return found
}

/** The seed: Déménagement before Canapé, Canapé with no date. */
async function restoreSeed(api: APIRequestContext): Promise<void> {
  const list = await projects(api)
  const move = named(list, MOVE)
  const sofa = named(list, SOFA)
  const others = list.filter((project) => project.id !== move.id && project.id !== sofa.id).map((project) => project.id)

  const reordered = await api.post('/api/finance/projects/reorder', { data: { ids: [move.id, sofa.id, ...others] } })
  expect(reordered.status(), await reordered.text()).toBe(200)
  const undated = await api.patch(`/api/finance/projects/${sofa.id}`, {
    headers: MERGE_PATCH,
    data: { dateMode: 'none', targetOn: null },
  })
  expect(undated.status(), await undated.text()).toBe(200)

  await expect
    .poll(
      async () => {
        const now = await projects(api)
        return [now.map((project) => project.name).filter((name) => name === MOVE || name === SOFA), named(now, SOFA).dateMode]
      },
      { message: 'the seed was never indexed back', timeout: 30_000 },
    )
    .toEqual([[MOVE, SOFA], 'none'])
}

/** Drags a handle onto a row with the mouse: dnd-kit starts after a few pixels of movement. */
async function dragOnto(page: Page, handle: Locator, target: Locator): Promise<void> {
  const from = await handle.boundingBox()
  const to = await target.boundingBox()
  if (!from || !to) throw new Error('the drag handle or the drop target is not on screen')

  const x = from.x + from.width / 2
  const y = from.y + from.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x, y - 10, { steps: 5 })
  await page.mouse.move(x, to.y + to.height / 4, { steps: 15 })
  await page.mouse.up()
}

const reorderAnswered = (page: Page) =>
  page.waitForResponse(
    (response) => response.url().includes('/api/finance/projects/reorder') && response.request().method() === 'POST',
  )

test('the projects are reordered with the mouse and the keyboard, and the order is kept', async ({ page, api }) => {
  await restoreSeed(api)
  const screen = new FinanceProjectsPage(page)

  try {
    // --- Given: Déménagement first, Canapé after it -------------------------
    await screen.open()
    await expect.poll(() => screen.order([MOVE, SOFA])).toEqual([MOVE, SOFA])
    await expect(screen.row(SOFA)).toContainText('après Déménagement')

    // --- When: Canapé is dragged to the top with the mouse ------------------
    const sofaId = named(await projects(api), SOFA).id
    const dropped = reorderAnswered(page)
    await dragOnto(page, screen.handle(SOFA), screen.row(MOVE))
    const response = await dropped
    expect(response.status(), await response.text()).toBe(200)
    expect((response.request().postDataJSON() as { ids: string[] }).ids[0]).toBe(sofaId)

    // --- Then: the order changes, and outlives a reload ---------------------
    await expect.poll(() => screen.order([MOVE, SOFA])).toEqual([SOFA, MOVE])
    await expect
      .poll(async () => (await projects(api)).map((project) => project.name).filter((name) => name === MOVE || name === SOFA), {
        message: 'the new order never reached the index',
        timeout: 30_000,
      })
      .toEqual([SOFA, MOVE])
    await page.reload()
    await expect.poll(() => screen.order([MOVE, SOFA])).toEqual([SOFA, MOVE])

    // --- When: the keyboard puts it back: Espace, flèche bas, Espace --------
    const back = reorderAnswered(page)
    await screen.handle(SOFA).focus()
    await page.keyboard.press('Space')
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('Space')
    expect((await back).status()).toBe(200)
    await expect.poll(() => screen.order([MOVE, SOFA])).toEqual([MOVE, SOFA])
  } finally {
    await restoreSeed(api)
  }
})

test('Canapé, without a date, is given a deadline that comes before the move it waits for', async ({ page, api }) => {
  await restoreSeed(api)
  const screen = new FinanceProjectsPage(page)

  try {
    await screen.open()
    await expect(screen.row(SOFA)).toContainText('Sans date')

    // --- When: a deadline at the end of January 2027 ------------------------
    await screen.row(SOFA).getByRole('button', { name: `Modifier ${SOFA}` }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('radio', { name: 'Date limite (au plus tard)' }).check()
    await dialog.getByLabel(/^Date limite\W*$/).fill('2027-01-31')
    const saved = page.waitForResponse(
      (response) => response.url().includes('/api/finance/projects/') && response.request().method() === 'PATCH',
    )
    await dialog.getByRole('button', { name: 'Enregistrer' }).click()
    expect((await saved).status()).toBe(200)

    // --- Then: the list shows it, and flags the move dated after it ---------
    await expect(dialog).toBeHidden()
    await expect(screen.row(SOFA)).toContainText('au plus tard le 31 janvier 2027')
    await expect(screen.row(SOFA).getByRole('alert')).toHaveText('Dépendance non respectée : Déménagement est prévu après.')
  } finally {
    await restoreSeed(api)
  }
})

interface PlannedProject {
  name: string
  position: number
  purchaseMonth: string | null
  isBeyondHorizon: boolean
}

interface ProjectPlanning {
  startMonth: string
  horizonMonths: number
  months: { month: string }[]
  projects: PlannedProject[]
}

const planning = async (api: APIRequestContext): Promise<ProjectPlanning> => {
  const response = await api.get('/api/finance/project-planning')
  expect(response.status(), await response.text()).toBe(200)

  return (await response.json()) as ProjectPlanning
}

/** The two seeded projects of the planning, in the planning's order. */
const seeded = (plan: ProjectPlanning): PlannedProject[] => plan.projects.filter((project) => project.name === MOVE || project.name === SOFA)

/** The sofa is not bought before the move: `YYYY-MM` strings compare as dates, null is beyond the horizon. */
const sofaNotBeforeMove = (list: PlannedProject[]): boolean => {
  const move = list.find((project) => project.name === MOVE)
  const sofa = list.find((project) => project.name === SOFA)
  if (!move || !sofa || move.purchaseMonth === null) return false

  return sofa.purchaseMonth === null || sofa.purchaseMonth >= move.purchaseMonth
}

test('the planning puts Canapé after Déménagement, and moving Canapé first changes the next reading', async ({ page, api }) => {
  await restoreSeed(api)

  try {
    // --- When: Maggie is asked when the sofa can be bought -------------------
    await new DashboardPage(page).open()
    const events = await new ChatPanel(page).send('Quand pourrai-je acheter le canapé ?')
    expect(calledTools(events)).toContain('get_project_planning')
    expect(toolResults(events)).toEqual(
      expect.arrayContaining([expect.objectContaining({ toolName: 'get_project_planning', status: 'success' })]),
    )

    // --- Then: the planning she read puts Déménagement first, Canapé after it
    const before = await planning(api)
    expect(before.horizonMonths).toBe(36)
    expect(before.months).toHaveLength(36)
    expect(before.months[0]?.month).toBe(before.startMonth)
    expect(seeded(before).map((project) => [project.name, project.position])).toEqual([
      [MOVE, 0],
      [SOFA, 1],
    ])
    expect(sofaNotBeforeMove(seeded(before)), 'the sofa is planned before the move it waits for').toBe(true)

    // --- When: Canapé is moved to the top -----------------------------------
    const list = await projects(api)
    const move = named(list, MOVE)
    const sofa = named(list, SOFA)
    const others = list.filter((project) => project.id !== move.id && project.id !== sofa.id).map((project) => project.id)
    const reordered = await api.post('/api/finance/projects/reorder', { data: { ids: [sofa.id, move.id, ...others] } })
    expect(reordered.status(), await reordered.text()).toBe(200)

    // --- Then: the next reading follows the order, the dependency still holds
    const after = await planning(api)
    expect(seeded(after).map((project) => [project.name, project.position])).toEqual([
      [SOFA, 0],
      [MOVE, 1],
    ])
    expect(sofaNotBeforeMove(seeded(after)), 'moving Canapé first must not buy it before Déménagement').toBe(true)
  } finally {
    await restoreSeed(api)
  }
})

test('the Gantt draws the purchase of Canapé after that of Déménagement, and follows a drag to the top', async ({ page, api }) => {
  await restoreSeed(api)
  const screen = new FinanceProjectsPage(page)

  try {
    // --- Given: Déménagement on a fixed date, Canapé after it ----------------
    await screen.open()
    await expect(screen.gantt).toBeVisible()
    await expect(screen.gantt.getByTestId('gantt-axis').locator('[data-month]')).toHaveCount(36)
    await expect(screen.gantt.getByTestId('gantt-today')).toBeVisible()

    // --- Then: the move has its padlock, the sofa is bought after it ---------
    await expect.poll(() => screen.ganttOrder([MOVE, SOFA])).toEqual([MOVE, SOFA])
    await expect(screen.ganttRow(MOVE).getByTestId('gantt-fixed')).toBeVisible()
    const movePurchase = await screen.purchaseAt(MOVE)
    expect(movePurchase, 'the fixed date falls inside the 36 months').not.toBeNull()
    const sofaPurchase = await screen.purchaseAt(SOFA)
    if (sofaPurchase === null) {
      await expect(screen.ganttRow(SOFA).getByTestId('gantt-beyond')).toBeVisible()
    } else {
      expect(sofaPurchase).toBeGreaterThanOrEqual(movePurchase as number)
    }

    const savingFrom = async (): Promise<number> =>
      Number(await screen.ganttRow(SOFA).getByTestId('gantt-saving').getAttribute('data-from'))
    const sofaSavingBefore = await savingFrom()

    // --- When: Canapé, which has no date, is dragged to the top --------------
    const dropped = reorderAnswered(page)
    await dragOnto(page, screen.handle(SOFA), screen.row(MOVE))
    expect((await dropped).status()).toBe(200)

    // --- Then: the planning is read again and the sofa's bar comes first -----
    await expect.poll(() => screen.ganttOrder([MOVE, SOFA]), { timeout: 30_000 }).toEqual([SOFA, MOVE])
    // Déménagement's fixed date is funded first, so the sofa moves up the list without saving later than before.
    expect(await savingFrom()).toBeLessThanOrEqual(sofaSavingBefore)
  } finally {
    await restoreSeed(api)
  }
})
