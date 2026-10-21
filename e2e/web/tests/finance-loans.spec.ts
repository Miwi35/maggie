import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { assistantText, calledTools, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * The early repayment penalty of a loan, by conversation (MAG-522, extends MAG-102).
 *
 * Seeded on the stock account: « Prêt immo », 98 000 € left at 1,80 %, its
 * penalty `exempt`. Told the penalty sits at the legal cap, Maggie lists the
 * loans, sets `legal_cap` on that one and simulates a total repayment — the
 * real `manage_loans`, only the model scripted (`91-loans-early-repayment.yaml`).
 *
 * What the journey asserts is the data, never her wording beyond the scripted
 * « plafond légal »: the stored mode, and the penalty the API computes,
 * min(6 months of interest = 882 €, 3 % of the capital = 2 940 €).
 *
 * The stock account because no journey of the owner may see a 98 000 € loan
 * in its sums; `retries: 0` and the mode put back before and after, because a
 * retry would start on what the first attempt left.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const MORTGAGE = 'Prêt immo'
const JSON_ONLY = { Accept: 'application/json' }
const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }

interface StoredLoan {
  id: string
  name: string
  prepaymentPenaltyMode: string
}

async function mortgage(api: APIRequestContext): Promise<StoredLoan> {
  const found = (await getCollection<StoredLoan>(api, '/api/loans?itemsPerPage=100')).find((loan) => loan.name === MORTGAGE)
  if (!found) throw new Error(`the seeded loan « ${MORTGAGE} » is missing`)

  return found
}

async function storedMode(api: APIRequestContext, id: string): Promise<string> {
  const response = await api.get(`/api/loans/${id}`, { headers: JSON_ONLY })
  expect(response.status(), await response.text()).toBe(200)

  return ((await response.json()) as StoredLoan).prepaymentPenaltyMode
}

/** The seed: no penalty, so the update is seen landing. */
async function restoreSeed(api: APIRequestContext): Promise<void> {
  const { id } = await mortgage(api)
  const response = await api.patch(`/api/loans/${id}`, { headers: MERGE_PATCH, data: { prepaymentPenaltyMode: 'exempt' } })
  expect(response.status(), await response.text()).toBe(200)

  await expect
    .poll(() => storedMode(api, id), { message: 'the seed was never indexed back', timeout: 30_000 })
    .toBe('exempt')
}

test.beforeEach(async ({ stockUser }) => {
  await restoreSeed(stockUser.api)
})

test.afterEach(async ({ stockUser }) => {
  await restoreSeed(stockUser.api)
})

test('« mon prêt immo a les IRA au plafond légal » sets the mode, and a total repayment costs 882 €', async ({
  stockUser,
}) => {
  const { api, page } = stockUser

  // Given « Prêt immo », 98 000 € at 1,80 %, its penalty not set to the legal cap.
  const { id } = await mortgage(api)
  expect(await storedMode(api, id)).toBe('exempt')

  // When I tell Maggie…
  await new DashboardPage(page).open()
  const chat = new ChatPanel(page)
  const events = await chat.send('Mon prêt immo a les IRA au plafond légal, combien coûte un remboursement total ?')

  // …she lists the loans, updates that one, then simulates — and every call went through.
  expect(calledTools(events)).toEqual(['manage_loans', 'manage_loans', 'manage_loans'])
  expect(toolResults(events)).toEqual([
    expect.objectContaining({ toolName: 'manage_loans', status: 'success' }),
    expect.objectContaining({ toolName: 'manage_loans', status: 'success' }),
    expect.objectContaining({ toolName: 'manage_loans', status: 'success' }),
  ])
  expect(assistantText(events)).toContain('plafond légal')

  // Then the loan reads `legal_cap`…
  await expect
    .poll(() => storedMode(api, id), { message: 'the mode never reached the loan', timeout: 30_000 })
    .toBe('legal_cap')

  // …and a total repayment costs min(882 €, 2 940 €).
  const simulation = await api.get(`/api/finance/loans/${id}/early-repayment`, { headers: JSON_ONLY })
  expect(simulation.status(), await simulation.text()).toBe(200)
  const repayment = (await simulation.json()) as Record<string, number | string>
  expect(repayment).toMatchObject({ amountCents: 9800000, penaltyMode: 'legal_cap', penaltyCents: 88200 })
  expect(repayment.realGainCents).toBe(Number(repayment.remainingInterestCents) - 88200)
})
