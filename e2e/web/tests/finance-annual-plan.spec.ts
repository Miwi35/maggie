import { test, expect, seedId } from '../fixtures/index.js'
import { assistantText, calledTools, toolResults } from '../helpers/agui.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * The yearly planning session (MAG-48), extending the finance journeys of
 * MAG-102.
 *
 * The session has no screen yet: this slice is the read side of the plan, over
 * HTTP and over MCP. So the channel under test is the conversation — and with
 * `LLM_PROVIDER=fake` (MAG-95) only the model is scripted: the tool loop, the
 * MCP client and the real `plan_annual_budget` call are the production ones.
 *
 * Which is why the arithmetic is asserted against the API and not against
 * Maggie's wording. She says "480,00 €" because `48-annual-plan.yaml` says so;
 * what proves the session works is that the plan, read back, holds last year's
 * expense as a candidate and suggests what that year cost.
 *
 * 2033 is a far-future literal, as in `35-create-event.yaml`: no other journey
 * and no twelve-month window of the module reaches it.
 */

const YEAR = 2033
const SOURCE_YEAR = YEAR - 1

interface PlannedCategory {
  categoryId: string
  lastYear: {
    consumedCents: number
    events: { label: string; amountCents: number; month: number }[]
  }
  decidedCents: number
  suggestedCents: number
  envelopeCents: number | null
}

test('asking Maggie to prepare the year reads what the year behind cost', async ({ page, api }) => {
  // A category of this attempt's own. CI retries once without reseeding, and a
  // replay sharing the category would read its own first attempt's expense
  // beside this one and suggest twice as much.
  const created = await api.post('/api/categories', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      name: `Festivals MAG-48, essai ${test.info().retry}`,
      obligation: 'optional',
    },
  })
  expect(created.status(), await created.text()).toBe(201)
  const categoryId = String(((await created.json()) as { id?: string }).id ?? '')
  expect(categoryId, 'the category the session looks at').not.toBe('')

  // Last year's big expense, and a small one the threshold has to leave out of
  // the candidate list while still counting it as consumed.
  for (const spend of [
    { label: 'FESTIVAL MAG-48 AN DERNIER', cents: -48000, day: `${SOURCE_YEAR}-07-18` },
    { label: 'BIERE MAG-48', cents: -900, day: `${SOURCE_YEAR}-07-19` },
  ]) {
    const response = await api.post('/api/transactions', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: {
        account: `/api/accounts/${seedId('e2e_account_checking')}`,
        category: `/api/categories/${categoryId}`,
        label: spend.label,
        amountCents: spend.cents,
        currency: 'EUR',
        bookedAt: spend.day,
        status: 'spent',
      },
    })
    expect(response.status(), await response.text()).toBe(201)
  }

  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send('Prépare ma planification annuelle, je repars de l\'année écoulée')

  // The tool really ran, against the real MCP server. A tool loop that wrote
  // markup as text instead of calling anything (a7b08cf) looks like activity
  // and reads nothing.
  expect(calledTools(events)).toContain('plan_annual_budget')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ toolName: 'plan_annual_budget', status: 'success' }),
    ]),
  )

  // Only the last step's text is the answer (MAG-229): the "je regarde" of the
  // first round must not be what is left on screen.
  expect(assistantText(events)).toContain('480,00 €')
  await expect(chat.bubbles("Je regarde ce que l'année écoulée a coûté.")).toHaveCount(0)

  // The plan, read back: the API is where the proof is.
  const response = await api.get(`/api/finance/annual-plan?year=${YEAR}`, {
    headers: { Accept: 'application/json' },
  })
  expect(response.status(), await response.text()).toBe(200)

  const plan = (await response.json()) as { sourceYear: number; categories: PlannedCategory[] }
  expect(plan.sourceYear).toBe(SOURCE_YEAR)

  const planned = plan.categories.find((category) => category.categoryId === categoryId)
  expect(planned, 'the category the year behind cost something is in the plan').toBeDefined()

  // Both spends counted; only the one over the threshold is a candidate to
  // reconsider one by one, as a positive amount in the month it fell in.
  expect(planned?.lastYear.consumedCents).toBe(48900)
  expect(
    planned?.lastYear.events.map((event) => [event.label, event.amountCents, event.month]),
  ).toEqual([['FESTIVAL MAG-48 AN DERNIER', 48000, 7]])

  // Nothing decided for 2033 and no envelope yet, so the suggestion is what
  // the year behind actually cost.
  expect(planned?.decidedCents).toBe(0)
  expect(planned?.envelopeCents).toBeNull()
  expect(planned?.suggestedCents).toBe(48900)
})
