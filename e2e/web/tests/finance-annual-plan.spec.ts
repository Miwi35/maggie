import { test, expect, seedId } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * The yearly planning session (MAG-48), extending the finance journeys of
 * MAG-102.
 *
 * The session has no screen yet: the API and the MCP tool are this slice, the
 * admin and the mobile journeys come with their own tickets. So the channel
 * under test here is the conversation — and with `LLM_PROVIDER=fake` (MAG-95)
 * only the model is scripted: the tool loop, the MCP client and the three real
 * `plan_annual_budget` calls are the production ones.
 *
 * Which is why the assertions end on the API rather than on Maggie's wording.
 * She says "300,00 €" because `48-annual-plan.yaml` says so; what proves
 * anything is that the year's plan, read back afterwards, holds the expense
 * planned in July and the envelope the session decided on.
 *
 * 2033 is a far-future literal, as in `35-create-event.yaml`: no other journey
 * and no twelve-month window of the module reaches it, so the session's rows
 * and the seed's can never be taken for one another.
 */

const YEAR = 2033
const SOURCE_YEAR = YEAR - 1

interface PlannedCategory {
  categoryId: string
  lastYear: {
    consumedCents: number
    events: { label: string; amountCents: number; month: number }[]
  }
  plannedCents: number
  suggestedCents: number
  envelopeCents: number | null
}

test('planning the year with Maggie writes the expense and the envelope it adds up to', async ({
  page,
  api,
}) => {
  // A category of this attempt's own. CI retries once without reseeding, and a
  // replay that planned into the same category would find last attempt's
  // expense beside its own and count 480,00 € where the test expects 240,00 €.
  const created = await api.post('/api/categories', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      name: `Festivals MAG-48, essai ${test.info().retry}`,
      obligation: 'optional',
    },
  })
  expect(created.status(), await created.text()).toBe(201)
  const categoryId = String(((await created.json()) as { id?: string }).id ?? '')
  expect(categoryId, 'the category the session plans into').not.toBe('')

  // Last year's big expense: the matter of the session, and what the review
  // has to find on its own.
  const lastYear = await api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      account: `/api/accounts/${seedId('e2e_account_checking')}`,
      category: `/api/categories/${categoryId}`,
      label: 'FESTIVAL MAG-48 AN DERNIER',
      amountCents: -48000,
      currency: 'EUR',
      bookedAt: `${SOURCE_YEAR}-07-18`,
      status: 'spent',
    },
  })
  expect(lastYear.status(), await lastYear.text()).toBe(201)

  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  // The two ULIDs are in the sentence because no fixture can name one; the
  // scenario captures them and carries them into the real call.
  const events = await chat.send(
    `Prépare ma planification annuelle : le festival coûtera 240 euros en juillet`
      + ` dans la catégorie ${categoryId}, sur le compte ${seedId('e2e_account_checking')}`,
  )

  // Three rounds of the same tool — read, plan, budget — and every one of them
  // worked. A tool loop that wrote markup as text instead of calling anything
  // (a7b08cf) looks like activity and leaves the plan empty.
  expect(calledTools(events).filter((tool) => tool === 'plan_annual_budget')).toHaveLength(3)
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ toolName: 'plan_annual_budget', status: 'success' }),
    ]),
  )
  expect(
    toolResults(events).filter((result) => result.status !== 'success'),
    'a round of the session failed',
  ).toEqual([])

  // Only the last step's text is the answer (MAG-229): the "je regarde" of the
  // first round must not be the bubble left on screen.
  await expect(chat.message(/C'est planifié/)).toBeVisible()
  await expect(chat.bubbles("Je regarde ce que l'année écoulée a coûté.")).toHaveCount(0)

  // The plan, read back: the API is where the proof is.
  const response = await api.get(`/api/finance/annual-plan?year=${YEAR}`, {
    headers: { Accept: 'application/json' },
  })
  expect(response.status(), await response.text()).toBe(200)

  const plan = (await response.json()) as { sourceYear: number; categories: PlannedCategory[] }
  expect(plan.sourceYear).toBe(SOURCE_YEAR)

  const planned = plan.categories.find((category) => category.categoryId === categoryId)
  expect(planned, 'the category the session planned into is in its own plan').toBeDefined()

  // What the year behind cost, and the one expense big enough to reconsider —
  // as a positive amount, in the month it fell in.
  expect(planned?.lastYear.consumedCents).toBe(48000)
  expect(
    planned?.lastYear.events.map((event) => [event.label, event.amountCents, event.month]),
  ).toEqual([['FESTIVAL MAG-48 AN DERNIER', 48000, 7]])

  // What the session wrote: the expense reserved, and the envelope decided.
  expect(planned?.plannedCents).toBe(24000)
  expect(planned?.envelopeCents).toBe(30000)
  // The planned total of the year ahead wins over what last year consumed.
  expect(planned?.suggestedCents).toBe(24000)

  // And the budget of that year agrees: 300,00 € budgeted, 240,00 € set aside,
  // 60,00 € still free — nothing consumed, because nothing has been paid.
  const budget = await api.get(`/api/finance/budget-status?year=${YEAR}`, {
    headers: { Accept: 'application/json' },
  })
  expect(budget.status(), await budget.text()).toBe(200)

  const envelopes = (await budget.json()) as {
    budgets: {
      categoryId: string
      amountCents: number
      plannedCents: number
      consumedCents: number
      availableCents: number
    }[]
  }
  const envelope = envelopes.budgets.find((line) => line.categoryId === categoryId)
  expect(envelope, "the session's envelope shows up in the year's budget").toBeDefined()
  expect(envelope?.amountCents).toBe(30000)
  expect(envelope?.plannedCents).toBe(24000)
  expect(envelope?.consumedCents).toBe(0)
  expect(envelope?.availableCents).toBe(6000)
})
