import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { FinancePlacementsPage } from '../pages/FinancePlacementsPage.js'

/**
 * The simulated placements (MAG-555, extends MAG-102), in Finance ›
 * Planification › Placements (own tab since MAG-633).
 *
 * Seeded: « PEA » (2 000 € then 150 € a month at 6 % over 20 years),
 * « Assurance-vie » (1 200 € a year at 3,5 % over 15 years) and « Livret jeune
 * de Léo » (500 € once at 3 % over 10 years). The journey creates a placement
 * of 100 € a month at 5 % over 10 years: the API compounds it monthly at
 * (1,05)^(1/12) − 1, deposits at the end of each month, to 15 436 €.
 *
 * Only this file writes the placements, and only the one it creates:
 * `finally` deletes it, so a retry starts where the first attempt did.
 *
 * The last journey (MAG-556) reads, as an account of its own, a PEA that a
 * trip leaves capital from (`63-finance-placement-withdrawal.yaml`): « Voyage
 * au Japon » costs 5 000 €, 1 500 € of it from the PEA, and a net saving
 * capacity of 500 € a month buys it six months on. It only reads.
 */

const NAME = 'Épargne de Léa'
const PATH = '/api/finance/placements'

interface StoredPlacement {
  id: string
  name: string
  initial: number
  deposit: number
  frequency: string
  annualRate: number
  years: number
  final: number
}

async function removeCreated(api: APIRequestContext): Promise<void> {
  for (const placement of await getCollection<StoredPlacement>(api, `${PATH}?pagination=false`)) {
    if (placement.name !== NAME) continue
    const deleted = await api.delete(`${PATH}/${placement.id}`)
    expect([204, 404], await deleted.text()).toContain(deleted.status())
  }
}

test('a placement of 100 € a month at 5 % over 10 years would reach 15 436 €', async ({ page, api }) => {
  await removeCreated(api)
  const screen = new FinancePlacementsPage(page)

  try {
    // --- Given: the three seeded placements ---------------------------------
    await screen.open()
    await expect(screen.row('PEA')).toContainText('150 € / mois · 2 000 € initial')
    await expect(screen.row('Assurance-vie')).toContainText('1 200 € / an')
    await expect(screen.row('Livret jeune de Léo')).toContainText('500 € une fois')
    await expect(screen.card).toContainText('250 € / mois de versements')

    // --- When: a placement of 100 € a month at 5 % over 10 years ------------
    const saved = page.waitForResponse((response) => response.url().endsWith(PATH) && response.request().method() === 'POST')
    await screen.create({ name: NAME, deposit: '100', rate: '5', years: '10' })
    expect((await saved).status()).toBe(201)

    // --- Then: its row, its final capital and its curve ----------------------
    const row = screen.row(NAME)
    await expect(row).toContainText('100 € / mois')
    await expect(row).toContainText('5 %')
    await expect(row).toContainText('10 ans')
    await expect(screen.finalCapital(NAME)).toHaveText('15 436 €')
    await expect(row).toHaveAttribute('aria-selected', 'true')
    await expect(screen.simulation().getByRole('img', { name: new RegExp(`^Courbe de ${NAME} sur 10 ans`) })).toBeVisible()
    await expect(screen.simulation()).toContainText('Retiré à 4 % par an, ce capital donnerait environ')
    await expect(screen.card).toContainText('350 € / mois de versements')

    // --- And: the database holds it, in cents and basis points -------------
    const stored = await waitForIndexed<StoredPlacement>(api, `${PATH}?pagination=false`, (placement) => placement.name === NAME, {
      what: `the placement « ${NAME} »`,
    })
    expect(stored).toMatchObject({ initial: 0, deposit: 10000, frequency: 'monthly', annualRate: 500, years: 10 })
    expect(Math.round(stored.final / 100)).toBe(15436)

    // --- And: it outlives a reload ------------------------------------------
    await page.reload()
    await expect(screen.finalCapital(NAME)).toHaveText('15 436 €')
  } finally {
    await removeCreated(api)
  }
})

const [ANCHOR_YEAR, ANCHOR_MONTH] = seedAnchorDate().split('-').map(Number)

/** « avril 2027 »: the month `offset` months after the anchor's, as the list spells it. */
const monthLabel = (offset: number): string =>
  new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(Date.UTC(ANCHOR_YEAR, ANCHOR_MONTH - 1 + offset, 1)),
  )

/** « 2027-04 »: the same month, as the API names it. */
const monthLabelKey = (offset: number): string => {
  const month = new Date(Date.UTC(ANCHOR_YEAR, ANCHOR_MONTH - 1 + offset, 1))

  return `${month.getUTCFullYear()}-${String(month.getUTCMonth() + 1).padStart(2, '0')}`
}

/** « 4 672 € », rounded to the euro as the placements screen rounds. */
const wholeEuros = (cents: number): string =>
  new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', minimumFractionDigits: 0, maximumFractionDigits: 0 })
    .format(Math.round(cents / 100))
    .replace(/\s/gu, ' ')

interface SimulationAnswer {
  withdrawals: { name: string; month: string; amountCents: number }[]
  withdrawn: number
  gapAtEnd: number
  finalWith: number
}

/**
 * Given a trip of 5 000 € paid 1 500 € from the PEA and 3 500 € from the salary,
 * When I open the PEA in Finance › Planification › Placements,
 * Then the capital is drawn with and without the withdrawal, the gap at the end
 * of the 20 years is told, and the trip is listed as the 1 500 € taken out the
 * month it is bought.
 */
test('a trip paid in part by the PEA is drawn with and without the 1 500 € it takes out', async ({ placementWithdrawalUser }) => {
  const { page, api } = placementWithdrawalUser
  const screen = new FinancePlacementsPage(page)

  // --- Given: the PEA, and the trip that leaves it ---------------------------
  await screen.open()
  await expect(screen.row('PEA')).toContainText('150 € / mois · 2 000 € initial')
  await expect(screen.row('PEA')).toHaveAttribute('aria-selected', 'true')

  // --- Then: the two curves, one dashed, one solid, and what they say --------
  const simulation = screen.simulation()
  await expect(simulation).toContainText('PEA · avec et sans retrait pour Voyage au Japon')
  await expect(screen.curveWithout()).toBeVisible()
  await expect(screen.curveWithout()).toHaveAttribute('stroke-dasharray', /\d/)
  await expect(screen.curveWith()).toBeVisible()
  await expect(simulation.getByText('capital sans retrait')).toBeVisible()
  await expect(simulation.getByText('capital avec retrait')).toBeVisible()
  await expect(simulation.getByRole('img', { name: /^Courbe de PEA sur 20 ans.* sans retrait, .* avec retrait$/ })).toBeVisible()

  // --- And: the trip, the month it is bought and the amount it takes ---------
  await expect(screen.withdrawals()).toHaveCount(1)
  await expect(screen.withdrawals().first()).toContainText('Voyage au Japon')
  await expect(screen.withdrawals().first()).toContainText(monthLabel(6))
  await expect(screen.withdrawals().first()).toContainText('−1 500 €')

  // --- And: the gap at the end is the API's own, in whole euros --------------
  const [pea] = await getCollection<{ id: string; name: string }>(api, '/api/finance/placements?pagination=false')
  expect(pea.name).toBe('PEA')
  const read = await api.get(`/api/finance/placements/${pea.id}/simulation`)
  expect(read.ok(), await read.text()).toBe(true)
  const answer = (await read.json()) as SimulationAnswer
  expect(answer.withdrawals).toHaveLength(1)
  expect(answer.withdrawals[0]).toMatchObject({ name: 'Voyage au Japon', amountCents: 150000 })
  expect(answer.withdrawals[0].month).toBe(monthLabelKey(6))
  expect(answer.withdrawn).toBe(150000)
  expect(answer.gapAtEnd).toBeGreaterThan(150000)

  await expect(screen.gap()).toHaveText(`Écart à 20 ans : −${wholeEuros(answer.gapAtEnd)}`)
  await expect(simulation.getByText('Capital après retrait').locator('xpath=following-sibling::*[1]')).toHaveText(wholeEuros(answer.finalWith))
  await expect(simulation).toContainText('Après ces retraits, retiré à 4 % par an')
})

/**
 * Given the seeded PEA (MAG-633, the mock-ups' Planification),
 * When I open Finance › Planification,
 * Then one row offers Projets · Prêts · Placements · Indépendance — no Frise 20 ans nor
 * Planning · Scénarios tab —, Placements draws the PEA with its curve, and
 * Indépendance holds the 20-year frise.
 */
test('Planification is one row of sub-views: Placements draws the PEA, Indépendance holds the frise', async ({ page }) => {
  const screen = new FinancePlacementsPage(page)

  await screen.open()
  const row = page.getByRole('tablist', { name: 'Planification' })
  await expect(row.getByRole('tab')).toHaveText(['Projets', 'Prêts', 'Placements', 'Indépendance'])
  await expect(page.getByRole('tab', { name: 'Frise 20 ans' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: 'Scénarios' })).toHaveCount(0)

  await expect(screen.row('PEA')).toBeVisible()
  await expect(screen.simulation().getByRole('img', { name: /^Courbe de PEA sur 20 ans/ })).toBeVisible()

  await row.getByRole('tab', { name: 'Indépendance' }).click()
  await expect(screen.content.getByTestId('timeline-panel')).toBeVisible()
  await expect(screen.card).toHaveCount(0)
})
