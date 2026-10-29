import { test, expect } from '../fixtures/index.js'
import { calledTools } from '../helpers/agui.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { FinanceProjectsPage } from '../pages/FinanceProjectsPage.js'

/**
 * « Et si je remboursais le prêt auto ? » — MAG-559, extends MAG-102.
 *
 * Maggie hands the question to the `advisor` sub-agent (`91-advisor-delegate.yaml`, then
 * `90-advisor-loan-early.yaml` for its own run): it reads, simulates the early repayment of the seeded
 * « Crédit voiture » and files ONE draft scenario. What is proved is the chain through the real tools
 * and what the API stored: a scenario of kind advisor, a draft, never current, with its repayment.
 * « Voir » and « Ignorer » are the user's gestures; the journey ignores the card at the end.
 */

const JSON_ONLY = { Accept: 'application/json' }

interface StoredScenario {
  id: string
  name: string
  kind: string
  status: string
  current: boolean
  repayments: { loanName?: string }[]
}

test('a trajectory question files an advisor draft, never current, that the user can ignore — MAG-559', async ({ page, api }) => {
  const list = async (): Promise<StoredScenario[]> => {
    const response = await api.get('/api/finance/scenarios', { headers: JSON_ONLY })
    expect(response.status(), await response.text()).toBe(200)

    return ((await response.json()) as { scenarios: StoredScenario[] }).scenarios
  }
  const advisorDrafts = async () => (await list()).filter((scenario) => scenario.kind === 'advisor')

  for (const stale of await advisorDrafts()) {
    await api.delete(`/api/finance/scenarios/${stale.id}`, { headers: JSON_ONLY })
  }
  const currentBefore = (await list()).filter((scenario) => scenario.current).map((scenario) => scenario.id)

  const screen = new FinanceProjectsPage(page)
  await screen.open()
  const events = await new ChatPanel(page).send('Et si je remboursais le prêt auto plus tôt ?')

  expect(calledTools(events), 'the advisor was never consulted — did the scenario stop matching?').toContain('delegate')

  await expect.poll(async () => (await advisorDrafts()).length, { message: 'no advisor draft was filed', timeout: 30_000 }).toBe(1)
  const [draft] = await advisorDrafts()
  expect(draft.status).toBe('draft')
  expect(draft.current).toBe(false)
  expect(draft.repayments).toHaveLength(1)
  expect((await list()).filter((scenario) => scenario.current).map((scenario) => scenario.id)).toEqual(currentBefore)

  await page.reload()
  await screen.openScenarios()
  const card = screen.scenarioCard(draft.name)
  await expect(card).toContainText('Suggestion du conseiller · brouillon')
  await expect(card.getByRole('button', { name: 'Voir' })).toBeVisible()

  await card.getByRole('button', { name: 'Ignorer' }).click()
  await expect(card).toHaveCount(0)
  expect(await advisorDrafts()).toHaveLength(0)
})
