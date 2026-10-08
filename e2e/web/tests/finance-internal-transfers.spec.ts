import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { expectRealtimeSync, openSubscribed } from '../helpers/mercure.js'
import { FinanceTransfersPage } from '../pages/FinanceTransfersPage.js'

/**
 * Internal transfers: seen, paired and corrected from the admin (MAG-272, on
 * MAG-102's journey).
 *
 * Written as the **neighbour**, for the reason `finance-accounts.spec.ts` gives:
 * the owner's balances and train de vie are asserted by other files, and two
 * accounts with 3 000,00 € moving between them would move both.
 *
 * The train de vie is read from the dashboard's own endpoint rather than from
 * its card: the card only shows it once an income is known, and the figure is
 * the one that matters. It is compared with itself — before the detection,
 * after it, after the correction — because the neighbour may hold other
 * history. The amount is a multiple of three so that the average over the
 * three-month sample moves by exactly a third of it.
 */

interface StoredTransaction {
  id?: string
  label?: string
  status?: string
  transferKind?: string
  transferSource?: string
  counterpart?: string | null
}

/** The 12th, 13th and 14th of last month: inside the sample, and inside the pairing window. */
function lastMonthDay(day: number): string {
  const [year, month] = seedAnchorDate().split('-').map(Number)
  const [y, m] = month === 1 ? [year - 1, 12] : [year, month - 1]

  return `${y}-${String(m).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

async function createAccount(api: APIRequestContext, name: string, type: string): Promise<string> {
  const response = await api.post('/api/accounts', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { name, type, currency: 'EUR', balanceCents: 0 },
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
  status: 'spent' | 'planned' = 'spent',
): Promise<string> {
  const response = await api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      account: `/api/accounts/${accountId}`,
      label,
      amountCents,
      currency: 'EUR',
      bookedAt,
      status,
    },
  })
  expect(response.status(), await response.text()).toBe(201)

  return ((await response.json()) as { id: string }).id
}

/**
 * Consumes a planned line.
 *
 * A line is paired as it is created, so two legs created spent would arrive
 * already marked and leave the catch-up nothing to do. Created planned — which
 * the detection ignores — and consumed afterwards, they are the history the
 * catch-up exists for.
 */
async function consume(api: APIRequestContext, transactionId: string): Promise<void> {
  const response = await api.patch(`/api/transactions/${transactionId}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { status: 'spent' },
  })
  expect(response.status(), await response.text()).toBe(200)
}

async function lifestyleCents(api: APIRequestContext): Promise<number> {
  const response = await api.get('/api/finance/dashboard')
  expect(response.status(), await response.text()).toBe(200)
  const dashboard = (await response.json()) as {
    savingCapacity: { estimatedLifestyleCents: number }
  }

  return dashboard.savingCapacity.estimatedLifestyleCents
}

/**
 * The neighbour's finance module must be left as it was found:
 * `finance-independence.spec.ts` declares a rente on it and expects no train de
 * vie yet, so 3 080,00 € left here would show up there as 1 026,66 € a month.
 * Deleting an account takes its transactions with it.
 */
const createdAccountIds: string[] = []

test.afterEach(async ({ otherUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await otherUser.api.delete(`/api/accounts/${id}`)
  }
})

test('a detected transfer wears its badge and its counterpart, and the owner can take it off', async ({
  otherUser,
}) => {
  const { api, page: actor } = otherUser
  const attempt = test.info().retry
  // Unique per attempt, amount included: CI retries once without reseeding, and
  // a first attempt that stopped before the detection leaves a 3 000,00 € leg
  // the second one would pair with instead of its own.
  const transferCents = 300_000 + attempt * 300
  const ordinaryCents = 8_000
  const checkingName = `Courant MAG-272, essai ${attempt}`
  const savingsName = `Livret MAG-272, essai ${attempt}`
  const debit = `VIREMENT VERS COURANT MAG-272, essai ${attempt}`
  const credit = `VIREMENT DEPUIS LIVRET MAG-272, essai ${attempt}`
  const ordinary = `ACHAT MAG-272, essai ${attempt}`

  const checkingId = await createAccount(api, checkingName, 'checking')
  const savingsId = await createAccount(api, savingsName, 'savings')
  createdAccountIds.push(checkingId, savingsId)
  const debitId = await createTransaction(api, savingsId, debit, -transferCents, lastMonthDay(12), 'planned')
  const creditId = await createTransaction(api, checkingId, credit, transferCents, lastMonthDay(13), 'planned')
  await createTransaction(api, checkingId, ordinary, -ordinaryCents, lastMonthDay(14))
  await consume(api, debitId)
  await consume(api, creditId)
  for (const label of [debit, credit, ordinary]) {
    await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
      (candidate) => candidate.label === label && candidate.status === 'spent',
      { what: `The line "${label}", consumed` },
    )
  }

  // Both legs count as spending until the detection runs: the debit does, the
  // credit is not a debit.
  const before = await lifestyleCents(api)

  const transfers = new FinanceTransfersPage(actor)
  const checking = new FinanceTransfersPage(await otherUser.secondWindow())

  await checking.openTransactions(checkingId)
  await expect(checking.row(credit)).toBeVisible()
  await expect(checking.badge(credit), 'nothing is marked before the detection').toHaveCount(0)

  // Subscribed before the detection publishes anything: an update sent while
  // the hub has not registered the subscriber is never delivered.
  await openSubscribed(checking.page, () => checking.openTransactions(checkingId))

  await transfers.openDetection()
  await transfers.detect.click()

  // What it would do, shown before it does it: the two legs, nothing written.
  await expect(transfers.preview).toContainText(debit)
  await expect(transfers.preview).toContainText(credit)
  const untouched = (await getCollection<StoredTransaction>(api, '/api/transactions?itemsPerPage=100'))
    .filter((candidate) => candidate.label === credit)
  expect(untouched.map((candidate) => candidate.transferKind)).not.toContain('internal')

  await expectRealtimeSync(
    checking.page,
    async () => {
      await transfers.confirmPairs.click()
      await waitForIndexed<StoredTransaction>(
        api,
        '/api/transactions?itemsPerPage=100',
        (candidate) => candidate.label === credit && candidate.transferKind === 'internal',
        { what: 'The credit leg, once marked' },
      )
    },
    async (observer) => {
      await expect(new FinanceTransfersPage(observer).badge(credit)).toBeVisible()
    },
  )

  // Each leg shows the other as its counterpart.
  await expect(checking.row(credit)).toContainText(`Contrepartie : ${savingsName}`)
  await expect(checking.row(credit)).toContainText(debit)
  await transfers.openTransactions(savingsId)
  await expect(transfers.badge(debit)).toBeVisible()
  await expect(transfers.row(debit)).toContainText(`Contrepartie : ${checkingName}`)
  await expect(transfers.row(debit)).toContainText(credit)

  // Only the 80,00 € is left in the train de vie: the transfer is no spending.
  expect(await lifestyleCents(api)).toBe(before - transferCents / 3)

  // The owner takes the marking off one leg: both lose it.
  await transfers.openTransaction(debit)
  await transfers.release.click()
  await expect(transfers.content.getByRole('button', { name: 'C’est un virement interne' })).toBeVisible()

  // The line the owner judged is sealed `manual`; the leg it pointed at is
  // freed and goes back to `auto`, so a third line may still claim it.
  const legs = await Promise.all(
    [
      { label: debit, source: 'manual' },
      { label: credit, source: 'auto' },
    ].map(({ label, source }) =>
      waitForIndexed<StoredTransaction>(
        api,
        '/api/transactions?itemsPerPage=100',
        (candidate) =>
          candidate.label === label &&
          candidate.transferKind === 'none' &&
          candidate.transferSource === source,
        { what: `The leg "${label}", once released` },
      ),
    ),
  )
  expect(legs.map((leg) => leg.counterpart ?? null)).toEqual([null, null])

  await expect(checking.badge(credit)).toHaveCount(0)
  expect(await lifestyleCents(api), 'the figure is back to where it started').toBe(before)

  // A judgement of the owner survives the next catch-up.
  await transfers.openDetection()
  await transfers.detect.click()
  await expect(transfers.preview).toBeVisible()
  await expect(transfers.preview).not.toContainText(credit)
})
