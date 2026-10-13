import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { waitForIndexed } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * Le salaire de référence (MAG-273 et MAG-274, extends MAG-102).
 *
 *   Étant donné une opération récurrente « Salaire » attendue à 4 950 € par mois
 *   Quand je demande à Maggie « sur quel salaire tu juges mon mois ? »
 *   Alors elle lit `get_reference_salary`
 *     et le repère est 4 950 €, source « attendu » (`expected`)
 *
 *   Étant donné la même opération « Salaire » et aucun salaire net déclaré sur le matelas (MAG-274)
 *   Quand je lis le matelas et le dashboard finance
 *   Alors la cible du matelas vaut 3 × 4 950,00 € et il n'est plus « non configuré »
 *     et le score du mois cite le salaire de référence (4 950 €, « attendu »)
 *
 * The salary is found by the standard key of « Salaire » (`salary`), never by
 * its name: the category comes from the standard set. What the journey asserts
 * is the tool that ran and the figure the endpoint gives — the fake does not
 * read tool results; each source is `GetReferenceSalaryTest`'s business.
 *
 * Signs in as the fourth account, which declares no salary on its cushion;
 * the series lives on an account of its own, deleted afterwards with it.
 * `retries: 0` because a retry would start on what the first attempt left.
 */

test.describe.configure({ retries: 0 })

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface StoredCategory {
  id?: string
  standardKey?: string | null
}

interface CushionStatus {
  targetMonths: number
  targetCents: number
  monthlyNetIncomeCents: number
  referenceSalarySource: string
  isConfigured: boolean
}

interface FinanceDashboard {
  score: { referenceSalary: { monthlyCents: number | null; source: string } }
  savingCapacity: { monthlyNetIncomeCents: number; isIncomeKnown: boolean }
}

interface ReferenceSalary {
  monthlyCents: number | null
  source: string
  isConfigured: boolean
  operations: Array<{ label: string }>
}

const createdAccountIds: string[] = []

/** Étant donné une opération « Salaire » attendue à 4 950 € par mois, sur un compte à elle. */
async function givenAnExpectedSalaryOf4950(api: APIRequestContext): Promise<void> {
  // The standard « Salaire », with its key — laid down, or already there.
  const installed = await api.post('/api/finance/categories/standard')
  expect(installed.status(), await installed.text()).toBe(200)
  const salary = await waitForIndexed<StoredCategory>(api, '/api/categories', (c) => c.standardKey === 'salary', {
    what: 'The standard « Salaire » category',
  })

  const account = await api.post('/api/accounts', {
    headers: LD,
    data: { name: 'Compte salaire MAG-273', type: 'checking', currency: 'EUR', balanceCents: 0 },
  })
  expect(account.status(), await account.text()).toBe(201)
  const accountId = String(((await account.json()) as { id: string }).id)
  createdAccountIds.push(accountId)

  // A « Salaire » series expected at 4 950 € on the 25th, running since last year.
  const series = await api.post('/api/recurring_operations', {
    headers: LD,
    data: {
      label: 'Salaire ACME MAG-273',
      counterpartyName: 'ACME MAG-273',
      account: `/api/accounts/${accountId}`,
      category: `/api/categories/${salary.id}`,
      period: 'monthly',
      anchorOn: '2025-01-25',
      referenceAmountCents: 495000,
      referenceSource: 'declared',
    },
  })
  expect(series.status(), await series.text()).toBe(201)
}

test.afterEach(async ({ stockUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await stockUser.api.delete(`/api/accounts/${id}`)
  }
})

test('« sur quel salaire tu juges mon mois ? » reads the expected salary', async ({ stockUser }) => {
  const { api, page } = stockUser

  await givenAnExpectedSalaryOf4950(api)

  // When I ask Maggie what salary she judges my month on.
  await new DashboardPage(page).open()
  const events = await new ChatPanel(page).send('Sur quel salaire tu juges mon mois ?')

  // Then she read the reference salary, and it went through.
  expect(calledTools(events)).toContain('get_reference_salary')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'get_reference_salary', status: 'success' })]),
  )

  // And the mark is 4 950 €, expected.
  const response = await api.get('/api/finance/reference-salary')
  expect(response.status(), await response.text()).toBe(200)
  const reference = (await response.json()) as ReferenceSalary
  expect(reference.monthlyCents).toBe(495000)
  expect(reference.source).toBe('expected')
  expect(reference.isConfigured).toBe(true)
  expect(reference.operations.map((operation) => operation.label)).toEqual(['Salaire ACME MAG-273'])
})

test('the cushion and the month follow the reference salary, declared nothing', async ({ stockUser }) => {
  const { api, page } = stockUser

  // Given the expected salary of 4 950 €, and no net salary declared on the cushion.
  await givenAnExpectedSalaryOf4950(api)

  // When I open the finance dashboard.
  await new DashboardPage(page).open()
  const cushionResponse = await api.get('/api/finance/cushion-status')
  expect(cushionResponse.status(), await cushionResponse.text()).toBe(200)
  const cushion = (await cushionResponse.json()) as CushionStatus
  const dashboardResponse = await api.get('/api/finance/dashboard')
  expect(dashboardResponse.status(), await dashboardResponse.text()).toBe(200)
  const dashboard = (await dashboardResponse.json()) as FinanceDashboard

  // Then the cushion's target is 3 × 4 950 € and it is no longer « not configured ».
  expect(cushion.monthlyNetIncomeCents).toBe(495000)
  expect(cushion.targetCents).toBe(cushion.targetMonths * 495000)
  expect(cushion.referenceSalarySource).toBe('expected')
  expect(cushion.isConfigured).toBe(true)

  // And the month's score and the saving capacity are both read against the same mark.
  expect(dashboard.score.referenceSalary).toEqual({ monthlyCents: 495000, source: 'expected' })
  expect(dashboard.savingCapacity.monthlyNetIncomeCents).toBe(495000)
  expect(dashboard.savingCapacity.isIncomeKnown).toBe(true)
})
