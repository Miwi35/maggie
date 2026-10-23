import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { getCollection } from '../helpers/api.js'
import { FinanceProjectsPage } from '../pages/FinanceProjectsPage.js'

/**
 * The scenarios of the projects (MAG-551, extends MAG-102).
 *
 * The seed has two projects; the journey adds two of its own so the three
 * offered scenarios (Rapide, Équilibré, Qualité de vie) have something to
 * order, and removes them in `finally`. No scenario is current at first. The
 * journey makes « Rapide » current, compares the scenarios, then makes one of
 * its own current instead, and the badge and the banner follow. A stored copy
 * of an offered scenario cannot be deleted through the API: it stays, with no
 * current scenario, which every other journey tolerates.
 *
 * The second journey (MAG-552) adds the total early repayment of the seeded
 * « Crédit voiture » to a scenario of its own: the repayment shows in the list
 * with the real gain the API figures (remaining interest − IRA) and its effect
 * on N. « Prêt immo » belongs to the stock account, not to this one.
 */

test.describe.configure({ mode: 'serial' })

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const JSON_ONLY = { Accept: 'application/json' }
const EXTRAS = ['Scénarios e2e : vélo', 'Scénarios e2e : piano']
const OWN = 'Plan e2e'
const REPAYING = 'Solde e2e'
const CAR_LOAN = 'Crédit voiture'

interface StoredScenario {
  id: string
  name: string
  kind: string
  current: boolean
}

const scenarios = async (api: APIRequestContext): Promise<StoredScenario[]> => {
  const response = await api.get('/api/finance/scenarios', { headers: JSON_ONLY })
  expect(response.status(), await response.text()).toBe(200)

  return ((await response.json()) as { scenarios: StoredScenario[] }).scenarios
}

async function cleanUp(api: APIRequestContext): Promise<void> {
  await api.delete('/api/finance/current-scenario', { headers: JSON_ONLY })
  for (const scenario of (await scenarios(api)).filter((candidate) => candidate.name === OWN)) {
    await api.delete(`/api/finance/scenarios/${scenario.id}`, { headers: JSON_ONLY })
  }
  const projects = await getCollection<{ '@id': string; name: string }>(api, '/api/finance/projects?itemsPerPage=100')
  for (const project of projects.filter((candidate) => EXTRAS.includes(candidate.name))) {
    await api.delete(project['@id'])
  }
}

test('the three offered scenarios are compared, one is made current, then one of the user', async ({ page, api }) => {
  await cleanUp(api)
  const screen = new FinanceProjectsPage(page)

  try {
    // --- Given: four projects, so the offered scenarios have an order to propose
    for (const [index, name] of EXTRAS.entries()) {
      const created = await api.post('/api/finance/projects', {
        headers: LD,
        data: { name, amountCents: 80_000 + index * 40_000, currency: 'EUR', wellbeing: 'comfort', dateMode: 'none', targetOn: null, dependsOn: [] },
      })
      expect(created.status(), await created.text()).toBe(201)
    }

    // --- When: the Scénarios tab is opened -----------------------------------
    await screen.open()
    await screen.openScenarios()

    // --- Then: the three offered scenarios, none current, no verdict ----------
    for (const name of ['Rapide', 'Équilibré', 'Qualité de vie']) {
      await expect(screen.scenarioCard(name)).toBeVisible()
      await expect(screen.scenarioCard(name).getByTestId('scenario-n')).toContainText('N : ')
    }
    await expect(screen.currentBanner).toHaveText(/Aucun scénario courant/)
    await expect(screen.content.getByText('Courant', { exact: true })).toHaveCount(0)
    await expect(screen.content.getByTestId('scenarios-panel')).not.toContainText(/recommand|meilleur/i)
    await expect(screen.scenarioCard('Rapide').getByRole('button', { name: 'Supprimer' })).toHaveCount(0)
    await expect(screen.scenarioCard('Rapide').getByRole('button', { name: 'Dupliquer' })).toBeVisible()

    // --- And: they are compared on one chart and one table --------------------
    const comparison = screen.content.getByTestId('scenario-comparison')
    await expect(comparison).toBeVisible()
    await expect(comparison.getByTestId('scenario-curve')).not.toHaveCount(0)
    await expect(comparison.getByTestId('comparison-row').first()).toBeVisible()

    // --- When: « Rapide » is made current --------------------------------------
    const made = page.waitForResponse((response) => response.url().includes('/make-current') && response.request().method() === 'POST')
    await screen.scenarioCard('Rapide').getByRole('button', { name: 'Rendre courant' }).click()
    expect((await made).status()).toBe(200)

    // --- Then: the banner names it with its N, and only it carries the badge ---
    await expect(screen.currentBanner).toContainText('Scénario courant · Rapide · N : ')
    await expect(screen.scenarioCard('Rapide').getByText('Courant', { exact: true })).toBeVisible()
    await expect(screen.content.getByText('Courant', { exact: true })).toHaveCount(1)
    expect((await scenarios(api)).filter((scenario) => scenario.current).map((scenario) => scenario.name)).toEqual(['Rapide'])

    // --- When: a scenario of the user is created -------------------------------
    await page.getByRole('button', { name: 'Scénario', exact: true }).click()
    const form = page.getByRole('form', { name: 'Nouveau scénario' })
    await form.getByLabel(/^Nom/).fill(OWN)
    const saved = page.waitForResponse((response) => response.url().endsWith('/api/finance/scenarios') && response.request().method() === 'POST')
    await form.getByRole('button', { name: 'Enregistrer' }).click()
    expect((await saved).status()).toBe(201)
    await expect(screen.scenarioCard(OWN)).toBeVisible()
    await expect(screen.scenarioCard(OWN).getByRole('button', { name: 'Supprimer' })).toBeVisible()

    // --- And: made current, it takes the badge from « Rapide » -----------------
    await screen.scenarioCard(OWN).getByRole('button', { name: 'Rendre courant' }).click()
    await expect(screen.currentBanner).toContainText(`Scénario courant · ${OWN} · N : `)
    await expect(screen.content.getByText('Courant', { exact: true })).toHaveCount(1)
    await expect(screen.scenarioCard(OWN).getByText('Courant', { exact: true })).toBeVisible()

    // --- Then: it outlives a reload ---------------------------------------------
    await page.reload()
    await screen.openScenarios()
    await expect(screen.currentBanner).toContainText(`Scénario courant · ${OWN}`)

    // --- When: the current scenario is withdrawn -------------------------------
    await screen.currentBanner.getByRole('button', { name: 'Retirer le courant' }).click()
    await expect(screen.currentBanner).toHaveText(/Aucun scénario courant/)
  } finally {
    await cleanUp(api)
  }
})

interface RepaymentFigures {
  loanName: string
  immobilizedCents: number
  penaltyCents: number
  interestSavedCents: number
  realGainCents: number
}

/** Two months from now, as the editor's month field takes it: `YYYY-MM`. */
const repaymentMonth = (): string => {
  const date = new Date()
  date.setMonth(date.getMonth() + 2)

  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

async function removeRepaying(api: APIRequestContext): Promise<void> {
  for (const scenario of (await scenarios(api)).filter((candidate) => candidate.name === REPAYING)) {
    await api.delete(`/api/finance/scenarios/${scenario.id}`, { headers: JSON_ONLY })
  }
}

test('a total early repayment is an element of the scenario, with its real gain and its effect on N', async ({ page, api }) => {
  await removeRepaying(api)
  const screen = new FinanceProjectsPage(page)

  try {
    // --- Given: the seeded loan « Crédit voiture » and the Scénarios tab -----------
    await screen.open()
    await screen.openScenarios()

    // --- When: a scenario repays all of that loan's capital --------------------
    await page.getByRole('button', { name: 'Scénario', exact: true }).click()
    const form = page.getByRole('form', { name: 'Nouveau scénario' })
    await form.getByLabel(/^Nom/).fill(REPAYING)
    await form.getByRole('button', { name: 'Ajouter un remboursement' }).click()
    await form.getByLabel('Prêt 1').click()
    await page.getByRole('option', { name: CAR_LOAN }).click()
    await form.getByLabel('Mois 1').fill(repaymentMonth())
    await form.getByRole('checkbox', { name: 'Tout le capital' }).check()
    const saved = page.waitForResponse((response) => response.url().endsWith('/api/finance/scenarios') && response.request().method() === 'POST')
    await form.getByRole('button', { name: 'Enregistrer' }).click()
    expect((await saved).status()).toBe(201)

    // --- Then: the repayment is listed in the scenario, with its figures --------
    const item = screen.scenarioCard(REPAYING).getByTestId('scenario-repayment-item')
    await expect(item).toContainText('Remboursement anticipé')
    await expect(item).toContainText(CAR_LOAN)
    await expect(item).toContainText('Total')
    const figures = item.getByTestId('scenario-repayment-figures')
    await expect(figures).toContainText(/Gain réel/)
    await expect(figures).toContainText(/N (−|\+) \d+ mois|N inchangée|N non atteinte/)

    // --- And: the API figured the real gain as the remaining interest minus the IRA
    const stored = (await scenarios(api)).find((scenario) => scenario.name === REPAYING)
    const detail = await api.get(`/api/finance/scenarios/${stored?.id}`, { headers: JSON_ONLY })
    expect(detail.status(), await detail.text()).toBe(200)
    const [effect] = ((await detail.json()) as { repaymentEffects: RepaymentFigures[] }).repaymentEffects
    expect(effect?.loanName).toBe(CAR_LOAN)
    expect(effect?.immobilizedCents).toBeGreaterThan(0)
    expect(effect?.interestSavedCents).toBeGreaterThan(0)
    expect(effect?.realGainCents).toBe((effect?.interestSavedCents ?? 0) - (effect?.penaltyCents ?? 0))
  } finally {
    await removeRepaying(api)
  }
})
