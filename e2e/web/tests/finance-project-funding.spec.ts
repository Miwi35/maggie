import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedId } from '../fixtures/index.js'
import { assistantText, calledTools, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * A project's funding sources, by conversation (MAG-524, extends MAG-102).
 *
 * Seeded on the stock account: « Voiture », 10 000 €, no source, and a savings
 * account outside the cushion, « Livret jeune ». Told to take 1 000 € from it
 * and borrow 6 000 € over 24 months at 4 %, Maggie reads the projects and the
 * accounts, then sets both sources — the real `manage_project_funding`, only
 * the model scripted (`91-projects-funding.yaml`).
 *
 * What the journey asserts is the data: two sources, 3 000 € left to the
 * salary, and a projected loan of 6 000 € among the loans.
 *
 * The stock account because no journey of the owner may see a 6 000 € loan or
 * a new savings account in its sums; `retries: 0` and the sources emptied
 * before and after, because a retry would start on what the first attempt left.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const JSON_ONLY = { Accept: 'application/json' }

interface FundingSource {
  kind: string
  amountCents: number
  accountId: string | null
  loanId: string | null
}

interface StoredProject {
  salaryCents: number
  fundingSources: FundingSource[]
}

interface StoredLoan {
  id: string
  projected: boolean
  principalRemainingCents: number
}

const carId = (): string => seedId('e2e_stock_project_car')

async function storedCar(api: APIRequestContext): Promise<StoredProject> {
  const response = await api.get(`/api/finance/projects/${carId()}`, { headers: JSON_ONLY })
  expect(response.status(), await response.text()).toBe(200)

  return (await response.json()) as StoredProject
}

/** The seed: no source, so no projected loan either. */
async function restoreSeed(api: APIRequestContext): Promise<void> {
  const response = await api.put(`/api/finance/projects/${carId()}/funding`, { data: { sources: [] } })
  expect(response.status(), await response.text()).toBe(200)
}

test.beforeEach(async ({ stockUser }) => {
  await restoreSeed(stockUser.api)
})

test.afterEach(async ({ stockUser }) => {
  await restoreSeed(stockUser.api)
})

test('« prends 1 000 € sur le Livret jeune et emprunte 6 000 € » leaves 3 000 € to the salary and projects a loan', async ({
  stockUser,
}) => {
  const { api, page } = stockUser

  // Given « Voiture », 10 000 €, funded by nothing yet.
  expect(await storedCar(api)).toMatchObject({ salaryCents: 1000000, fundingSources: [] })

  // When I tell Maggie…
  await new DashboardPage(page).open()
  const chat = new ChatPanel(page)
  const events = await chat.send('Pour la voiture, prends 1 000 € sur le Livret jeune et emprunte 6 000 € sur 24 mois à 4 %')

  // …she reads the projects and the accounts, then sets the sources — and every call went through.
  expect(calledTools(events)).toEqual(['manage_projects', 'manage_accounts', 'manage_project_funding'])
  expect(toolResults(events)).toEqual([
    expect.objectContaining({ toolName: 'manage_projects', status: 'success' }),
    expect.objectContaining({ toolName: 'manage_accounts', status: 'success' }),
    expect.objectContaining({ toolName: 'manage_project_funding', status: 'success' }),
  ])
  expect(assistantText(events)).toContain('3 000 €')

  // Then the project has both sources and 3 000 € left to the salary…
  const car = await storedCar(api)
  expect(car.salaryCents).toBe(300000)
  expect(car.fundingSources).toEqual([
    { kind: 'savings', amountCents: 100000, accountId: seedId('e2e_stock_account_livret_jeune'), loanId: null },
    { kind: 'loan', amountCents: 600000, accountId: null, loanId: expect.any(String) },
  ])

  // …and the loans hold a projected one of 6 000 €, the one the source names.
  const loanId = car.fundingSources[1].loanId
  await expect
    .poll(
      async () => (await getCollection<StoredLoan>(api, '/api/loans?itemsPerPage=100')).find((loan) => loan.id === loanId),
      { message: 'the projected loan never reached the index', timeout: 30_000 },
    )
    .toMatchObject({ projected: true, principalRemainingCents: 600000 })
})
