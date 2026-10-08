import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { FinanceTransfersPage } from '../pages/FinanceTransfersPage.js'

/**
 * Rejected payments: recognised as they arrive, shown as such, and left out of
 * the figures (MAG-350, on MAG-102's journey).
 *
 * Written as the **neighbour**, like `finance-internal-transfers.spec.ts` and
 * for its reason: the owner's figures are asserted elsewhere. The two lines
 * are created through the API rather than through the mocked bank: the bank
 * fixture is shared by every journey, and a 206,00 € debit added to it would
 * move their figures. Both paths end in the same detection, and the import's
 * call to it is covered by `ImportStatementTest`.
 */

interface StoredTransaction {
  label?: string
  transferKind?: string
}

/** A day of last month: inside the three-month sample of the train de vie. */
function lastMonthDay(day: number): string {
  const [year, month] = seedAnchorDate().split('-').map(Number)
  const [y, m] = month === 1 ? [year - 1, 12] : [year, month - 1]

  return `${y}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

async function createAccount(api: APIRequestContext, name: string): Promise<string> {
  const response = await api.post('/api/accounts', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { name, type: 'checking', currency: 'EUR', balanceCents: 0 },
  })
  expect(response.status(), await response.text()).toBe(201)

  return ((await response.json()) as { id: string }).id
}

async function createTransaction(
  api: APIRequestContext,
  accountId: string,
  label: string,
  amountCents: number,
  bookedAt: string,
): Promise<void> {
  const response = await api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { account: `/api/accounts/${accountId}`, label, amountCents, currency: 'EUR', bookedAt, status: 'spent' },
  })
  expect(response.status(), await response.text()).toBe(201)
}

async function lifestyleCents(api: APIRequestContext): Promise<number> {
  const response = await api.get('/api/finance/dashboard')
  expect(response.status(), await response.text()).toBe(200)

  return ((await response.json()) as { savingCapacity: { estimatedLifestyleCents: number } }).savingCapacity
    .estimatedLifestyleCents
}

/** Deleting the account takes its lines with it: the neighbour is left as found. */
const createdAccountIds: string[] = []

test.afterEach(async ({ otherUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await otherUser.api.delete(`/api/accounts/${id}`)
  }
})

test('a rejected direct debit and its REJET credit read as such and leave the spending', async ({ otherUser }) => {
  const { api, page } = otherUser
  const attempt = test.info().retry
  const accountName = `Courant MAG-350, essai ${attempt}`
  const debit = `PRELEVEMENT ELECTRICITE DE FRANCE MAG-350 ESSAI ${attempt}`
  const credit = `REJET PRLV ELECTRICITE DE FRANCE MAG-350 ESSAI ${attempt}`

  const before = await lifestyleCents(api)

  const accountId = await createAccount(api, accountName)
  createdAccountIds.push(accountId)
  await createTransaction(api, accountId, debit, -20_600, lastMonthDay(5))
  await createTransaction(api, accountId, credit, 20_600, lastMonthDay(6))

  for (const label of [debit, credit]) {
    await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
      (candidate) => candidate.label === label && candidate.transferKind === 'rejected',
      { what: `The line "${label}", recognised as a rejection` },
    )
  }

  // The 206,00 € never left: the train de vie does not count it.
  expect(await lifestyleCents(api)).toBe(before)

  const account = new FinanceTransfersPage(page)
  await account.openTransactions(accountId)
  await expect(account.row(debit).getByText('Rejeté', { exact: true })).toBeVisible()
  await expect(account.row(credit)).toContainText('Rejet de')
  await expect(account.row(credit)).toContainText(debit)

  // The owner disagrees: both lines go back to ordinary ones.
  await account.openTransaction(credit)
  await account.content.getByRole('button', { name: 'Ce n’est pas un rejet' }).click()
  await expect(account.content.getByRole('button', { name: 'C’est un rejet' })).toBeVisible()
  for (const label of [debit, credit]) {
    await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
      (candidate) => candidate.label === label && candidate.transferKind === 'none',
      { what: `The line "${label}", once released` },
    )
  }
})
