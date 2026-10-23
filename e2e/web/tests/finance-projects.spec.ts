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
 * The funding chart under the Gantt (MAG-528): a project on a fixed date that asks more than
 * the saving capacity can give makes the chart flag the months before it, and hovering one reads the excess.
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
  months: { month: string; isOverCapacity: boolean }[]
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

/**
 * The funding of a project, on the page (MAG-525, extends MAG-102).
 *
 * On the stock account, which holds « Livret jeune » (outside the cushion): a
 * project made for the journey, so it never meets the chat journey that sets
 * « Voiture »'s sources. 1 000 € of savings and a 6 000 € loan over 24 months
 * at 4 % leave 3 000 € to the salary, and the three options are priced side by
 * side with nothing recommended. Over-funding is refused with the API's words
 * and keeps the stored sources. The project and its sources are removed in
 * `finally`: a projected loan must not stay in the stock account's sums.
 */
test.describe('funding', () => {
  test.describe.configure({ retries: 0 })

  const BIKE = 'Vélo cargo e2e'
  const JSON_ONLY = { Accept: 'application/json' }

  interface FundedProject {
    id: string
    salaryCents: number
    fundingSources: { kind: string; amountCents: number }[]
  }

  const storedBike = async (api: APIRequestContext): Promise<FundedProject> => {
    const list = await getCollection<FundedProject & { name: string }>(api, '/api/finance/projects?itemsPerPage=100')
    const found = list.find((project) => project.name === BIKE)
    if (!found) throw new Error(`the project « ${BIKE} » is missing`)

    return found
  }

  const removeBike = async (api: APIRequestContext): Promise<void> => {
    const list = await getCollection<FundedProject & { name: string }>(api, '/api/finance/projects?itemsPerPage=100')
    for (const project of list.filter((candidate) => candidate.name === BIKE)) {
      await api.put(`/api/finance/projects/${project.id}/funding`, { data: { sources: [] } })
      await api.delete(`/api/finance/projects/${project.id}`)
    }
  }

  test('1 000 € of savings and a 6 000 € loan leave 3 000 € to the salary, the options side by side, an excess is refused', async ({
    stockUser,
  }) => {
    const { api, page } = stockUser
    await removeBike(api)
    const screen = new FinanceProjectsPage(page)

    try {
      // --- Given: a 10 000 € project funded by nothing -------------------------
      const created = await api.post('/api/finance/projects', {
        headers: { Accept: 'application/ld+json' },
        data: { name: BIKE, amountCents: 1000000, currency: 'EUR', wellbeing: 'medium', dateMode: 'none', targetOn: null, dependsOn: [], note: null },
      })
      expect(created.status(), await created.text()).toBe(201)
      await screen.open()
      const detail = await screen.openFunding(BIKE)
      await expect(detail.getByTestId('funding-remainder')).toContainText(/10\s000,00\s€ sur salaire/)

      // --- When: 1 000 € from the Livret jeune and a 6 000 € loan, 24 months at 4 %
      await screen.addSource(BIKE, 'Économies', 1000)
      await detail.getByLabel('Compte (économies)').click()
      await page.getByRole('option', { name: 'Livret jeune' }).click()
      await screen.addSource(BIKE, 'Emprunt', 6000)
      await detail.getByLabel('Taux annuel (%)', { exact: true }).fill('4')
      await detail.getByLabel(/^Durée \(mois\)/).fill('24')
      await expect(detail.getByTestId('funding-remainder')).toContainText(/3\s000,00\s€ sur salaire/)
      const saved = page.waitForResponse((response) => response.url().endsWith('/funding') && response.request().method() === 'PUT')
      await detail.getByRole('button', { name: 'Enregistrer les sources' }).click()
      expect((await saved).status()).toBe(200)

      // --- Then: the sources are stored, and are there after a reload -----------
      const stored = await storedBike(api)
      expect(stored.salaryCents).toBe(300000)
      expect(stored.fundingSources.map((source) => [source.kind, source.amountCents])).toEqual([
        ['savings', 100000],
        ['loan', 600000],
      ])
      await page.reload()
      const reloaded = await screen.openFunding(BIKE)
      await expect(reloaded.getByTestId('funding-source')).toHaveCount(2)
      await expect(reloaded.getByTestId('funding-remainder')).toContainText(/3\s000,00\s€ sur salaire/)

      // --- And: cash, loan and partial are priced side by side, none singled out
      await expect(screen.options(BIKE)).toHaveCount(3)
      await expect(screen.options(BIKE).nth(0)).toContainText(/10\s000,00\s€/)
      await expect(screen.options(BIKE).nth(1)).toContainText(/10\s421,86\s€/)
      const classes = await screen.options(BIKE).evaluateAll((columns) => columns.map((column) => column.className))
      expect(new Set(classes).size).toBe(1)
      await expect(reloaded).not.toContainText(/recommand|préférable/i)

      // --- When: the savings are raised so the sources exceed the project --------
      await reloaded.getByLabel('Montant économies (€)', { exact: true }).fill('9000')
      const refused = page.waitForResponse((response) => response.url().endsWith('/funding') && response.request().method() === 'PUT')
      await reloaded.getByRole('button', { name: 'Enregistrer les sources' }).click()

      // --- Then: the API's refusal is shown as worded, and nothing changed ------
      expect((await refused).status()).toBe(400)
      await expect(reloaded.getByRole('alert')).toBeVisible()
      const unchanged = await (await api.get(`/api/finance/projects/${stored.id}`, { headers: JSON_ONLY })).json()
      expect(unchanged.fundingSources.map((source: { amountCents: number }) => source.amountCents)).toEqual([100000, 600000])
    } finally {
      await removeBike(api)
    }
  })
})

/** `YYYY-MM-01` of the month `ahead` months from now: the date a fixed project is locked on. */
const firstOfMonth = (ahead: number): string => {
  const date = new Date()
  date.setUTCDate(1)
  date.setUTCMonth(date.getUTCMonth() + ahead)

  return date.toISOString().slice(0, 10)
}

test('the funding chart flags the months a fixed date asks more than the capacity, and hovering one reads the excess', async ({ page, api }) => {
  await restoreSeed(api)
  const screen = new FinanceProjectsPage(page)
  const { retry } = test.info()
  const name = `Mariage${0 === retry ? '' : ` essai ${retry}`}`
  let createdIri: string | null = null

  try {
    // --- Given: a project of 200 000 € locked two months ahead, far above any capacity
    const created = await api.post('/api/finance/projects', {
      headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
      data: {
        name,
        amountCents: 20_000_000,
        currency: 'EUR',
        wellbeing: 'comfort',
        dateMode: 'fixed',
        targetOn: firstOfMonth(2),
        dependsOn: [],
      },
    })
    expect(created.status(), await created.text()).toBe(201)
    createdIri = ((await created.json()) as { '@id': string })['@id']

    // --- When: the projects page is opened -----------------------------------
    await screen.open()

    // --- Then: the chart sits under the Gantt, on the same 36 months ---------
    await expect(screen.fundingChart).toBeVisible()
    await expect(screen.fundingChart.getByTestId('funding-bar')).toHaveCount(36)
    await expect(screen.fundingChart.getByTestId('funding-axis').locator('[data-month]')).toHaveCount(36)
    await expect(screen.fundingChart.getByTestId('funding-legend')).toContainText('Salaire')
    await expect(screen.fundingChart.getByTestId('funding-legend')).toContainText('Économies')
    await expect(screen.fundingChart.getByTestId('funding-legend')).toContainText('Capital libéré')
    await expect(screen.fundingChart.getByTestId('funding-capacity')).toBeVisible()

    // --- Then: the months before the date are flagged, the later ones are not
    const plan = await planning(api)
    const expected = plan.months
      .slice(0, 3)
      .filter((month) => month.isOverCapacity)
      .map((month) => month.month)
    expect(expected.length, 'the fixed date asks more than the capacity of its first months').toBeGreaterThan(0)
    await expect.poll(() => screen.flaggedMonths(), { timeout: 30_000 }).toEqual(expect.arrayContaining(expected))
    await expect(screen.fundingBar(plan.months[35]?.month ?? '')).not.toHaveAttribute('data-over', 'true')
    await expect(screen.fundingBar(expected[0] as string).getByTestId('funding-segment').first()).toBeVisible()

    // --- When: the cursor rests on a flagged month ---------------------------
    await screen.fundingBar(expected[0] as string).hover()

    // --- Then: the excess over the capacity is readable ----------------------
    await expect(page.getByRole('tooltip')).toContainText('Dépassement')
    await expect(page.getByRole('tooltip')).toContainText('Capacité')
  } finally {
    if (createdIri) await api.delete(createdIri)
    await restoreSeed(api)
  }
})

/**
 * An investment, on the page (MAG-557, extends MAG-102).
 *
 * « Box à louer » is made for the journey, locked two months ahead so its purchase always falls inside the
 * 36 months whatever the seeded capacity. Defined as an investment from its card — 1 000 € down, a loan of
 * 800 € over 12 months at 3 %, 50 € of rent, resold a year on for 600 € net to fund « Canapé » — the Gantt draws
 * the loan, the rent and the resale point, and Canapé is shown as funded by that resale. The investment, the
 * project and the seed are put back in `finally`: the resale funding on Canapé goes with the investment.
 */
test('defining an investment with a resale puts the loan, the rent and the resale point on the Gantt', async ({ page, api }) => {
  await restoreSeed(api)
  const screen = new FinanceProjectsPage(page)
  const { retry } = test.info()
  const name = `Box à louer${0 === retry ? '' : ` essai ${retry}`}`
  const resaleMonth = firstOfMonth(14).slice(0, 7)
  let createdIri: string | null = null

  try {
    // --- Given: the seed, and a Box locked two months ahead -------------------
    const created = await api.post('/api/finance/projects', {
      headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
      data: {
        name,
        amountCents: 180_000,
        currency: 'EUR',
        wellbeing: 'comfort',
        dateMode: 'fixed',
        targetOn: firstOfMonth(2),
        dependsOn: [],
      },
    })
    expect(created.status(), await created.text()).toBe(201)
    createdIri = ((await created.json()) as { '@id': string })['@id']
    await screen.open()
    await expect(screen.row(name)).toBeVisible()
    await screen.openFunding(name)

    // --- When: the owner defines the investment with a resale for Canapé -----
    const saved = page.waitForResponse((response) => response.url().endsWith('/investment') && response.request().method() === 'PUT')
    await screen.defineInvestment(name, {
      downPayment: '1000',
      rent: '50',
      rate: '3',
      months: '12',
      resaleMonth,
      resaleNet: '600',
      target: SOFA,
    })
    expect((await saved).status()).toBe(200)

    // --- Then: the card says what it costs and what it brings ----------------
    await expect(screen.investmentFigure(name, 'price')).toContainText(/1\s800,00\s€/)
    await expect(screen.investmentFigure(name, 'down-payment')).toContainText(/1\s000,00\s€/)
    await expect(screen.investmentFigure(name, 'loan')).toContainText(/800,00\s€/)
    await expect(screen.investmentFigure(name, 'rent')).toContainText(/50,00\s€ \/ mois/)
    await expect(screen.investmentCard(name).getByTestId('investment-resale')).toContainText(/net 600,00\s€ · finance Canapé/)

    // --- And: the Gantt shows the loan, the rent and the resale point --------
    await expect(screen.ganttRow(name).getByTestId('gantt-loan')).toBeVisible()
    await expect(screen.ganttRow(name).getByTestId('gantt-rent')).toBeVisible()
    await expect(screen.ganttRow(name).getByTestId('gantt-resale')).toBeVisible()
    await expect(screen.ganttRow(SOFA).getByTestId('gantt-resale-in')).toBeVisible()

    // --- And: the API kept it, with Canapé as the target ---------------------
    const stored = (await (await api.get(createdIri, { headers: { Accept: 'application/ld+json' } })).json()) as {
      investment: { monthlyRentCents: number; resale: { month: string; netAmountCents: number } | null }
    }
    expect(stored.investment.monthlyRentCents).toBe(5_000)
    expect(stored.investment.resale).toMatchObject({ month: resaleMonth, netAmountCents: 60_000 })

    // --- And: Canapé is funded by that resale, read-only ---------------------
    await screen.row(SOFA).getByRole('button', { name: `Financement de ${SOFA}` }).click()
    await expect(screen.funding(SOFA).getByTestId('funding-source').filter({ hasText: `Revente de ${name}` })).toBeVisible()
  } finally {
    if (createdIri) {
      await api.delete(`${createdIri}/investment`)
      await api.delete(createdIri)
    }
    await restoreSeed(api)
  }
})

