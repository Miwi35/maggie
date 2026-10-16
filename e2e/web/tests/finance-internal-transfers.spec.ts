import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { expectRealtimeSync, openSubscribed } from '../helpers/mercure.js'
import { FinanceImportPage } from '../pages/FinanceImportPage.js'
import { FinanceTransfersPage } from '../pages/FinanceTransfersPage.js'

/**
 * Internal transfers and rejected payments: seen, paired and corrected from
 * the admin (MAG-272 and MAG-350, on MAG-102's journey).
 *
 * Written as an account of its own (`transfersUser`), not the owner's nor the
 * neighbour's: the owner's balances and train de vie are asserted by other
 * files, and two accounts with 3 000,00 € moving between them would move both;
 * the neighbour's train de vie is moved by the debits other files book on it
 * while this one runs on another worker (a rent of 900,00 € read here as
 * 600,00 € a month).
 *
 * The train de vie is read from the dashboard's own endpoint rather than from
 * its card: the card only shows it once an income is known, and the figure is
 * the one that matters. It is compared with itself — before the detection,
 * after it, after the correction. The amount is a multiple of three so that
 * the average over the three-month sample moves by exactly a third of it.
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

/** What the dashboard counts as the month's income: transfers and rejections are not income. */
async function monthIncomeCents(api: APIRequestContext, month: string): Promise<number> {
  const response = await api.get('/api/finance/dashboard')
  expect(response.status(), await response.text()).toBe(200)
  const dashboard = (await response.json()) as {
    monthlyFlows: Array<{ month: string; incomeCents: number }>
  }

  return dashboard.monthlyFlows.find((flow) => flow.month === month)?.incomeCents ?? 0
}

/** `2026-09-12` → `12/09/2026`, the day format of a French bank's export. */
function frenchDate(isoDay: string): string {
  const [year, month, day] = isoDay.split('-')

  return `${day}/${month}/${year}`
}

/**
 * The account's finance module is left as it was found, so a retry starts from
 * the figure the first attempt started from. Deleting an account takes its
 * transactions with it.
 */
const createdAccountIds: string[] = []

// One after the other: the journeys read this account's train de vie, and
// each one's lines would move the figure the other compares with itself.
test.describe.configure({ mode: 'serial' })

test.afterEach(async ({ transfersUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await transfersUser.api.delete(`/api/accounts/${id}`)
  }
})

test('a detected transfer wears its badge and its counterpart, and the owner can take it off', async ({
  transfersUser,
}) => {
  const { api, page: actor } = transfersUser
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
  const checking = new FinanceTransfersPage(await transfersUser.secondWindow())

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

/**
 * A rejected payment (MAG-350): the debit and the REJET credit that gives it
 * back, on the same account. The lines are created through the API rather
 * than through the mocked bank, whose fixture every journey shares; an import
 * or a sync reaches the same detection through the same event
 * (`TransactionEffectsTest`, `ImportStatementTest`).
 */
test('a rejected direct debit and its REJET credit read as such and leave the spending', async ({ transfersUser }) => {
  const { api, page } = transfersUser
  const attempt = test.info().retry
  const accountName = `Courant MAG-350, essai ${attempt}`
  const debit = `PRELEVEMENT ELECTRICITE DE FRANCE MAG-350 ESSAI ${attempt}`
  const credit = `REJET PRLV ELECTRICITE DE FRANCE MAG-350 ESSAI ${attempt}`

  const before = await lifestyleCents(api)

  const accountId = await createAccount(api, accountName, 'checking')
  createdAccountIds.push(accountId)
  // Created spent, the credit is paired as it lands: what a sync does.
  await createTransaction(api, accountId, debit, -20_600, lastMonthDay(5))
  await createTransaction(api, accountId, credit, 20_600, lastMonthDay(6))

  for (const label of [debit, credit]) {
    await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?transferKind=rejected&itemsPerPage=100',
      (candidate) => candidate.label === label && candidate.transferKind === 'rejected',
      { what: `The line "${label}", recognised as a rejection` },
    )
  }

  // The 206,00 € never left: the train de vie does not count it.
  expect(await lifestyleCents(api)).toBe(before)

  const account = new FinanceTransfersPage(page)
  await account.openTransactions(accountId)

  // The pair is no operation of the account (MAG-375): the list leaves it out
  // and the Incidents tab reads it as one line.
  await expect(account.incidentsTab(1)).toBeVisible()
  await expect(account.row(debit)).toHaveCount(0)
  await expect(account.row(credit)).toHaveCount(0)

  await account.incidentsTab(1).click()
  const incident = account.row('ELECTRICITE DE FRANCE')
  await expect(incident).toHaveCount(1)
  await expect(incident).toContainText('Prélèvement rejeté')
  await expect(incident).toContainText('206,00')

  // A click opens the two original operations.
  await incident.click()
  await expect(account.incidentDetail).toContainText(debit)
  await expect(account.incidentDetail).toContainText(credit)

  // The owner disagrees: both lines go back to ordinary ones.
  await account.incidentDetail.getByRole('link', { name: 'Ouvrir la fiche' }).last().click()
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

  // Back in the list, and gone from the incidents.
  await account.openTransactions(accountId)
  await expect(account.row(debit)).toBeVisible()
  await expect(account.row(credit)).toBeVisible()
  await expect(account.incidentsTab(0)).toBeVisible()
})

/**
 * « Régularisé » (MAG-424): a rejection the owner dealt with leaves the pile
 * for the folded « Régularisés » section, and « Annuler » puts it back.
 * Created the way the MAG-350 journey creates them, through the API.
 */
test('a settled rejection leaves the pile for « Régularisés », and « Annuler » brings it back', async ({
  transfersUser,
}) => {
  const { api, page } = transfersUser
  const attempt = test.info().retry
  const accountId = await createAccount(api, `Courant MAG-424, essai ${attempt}`, 'checking')
  createdAccountIds.push(accountId)
  const rejections = [
    { payee: 'EDF', debit: `PRELEVEMENT EDF MAG-424 ESSAI ${attempt}`, credit: `REJET PRLV EDF MAG-424 ESSAI ${attempt}`, cents: 20_600, day: 5 },
    { payee: 'ORANGE', debit: `PRELEVEMENT ORANGE MAG-424 ESSAI ${attempt}`, credit: `REJET PRLV ORANGE MAG-424 ESSAI ${attempt}`, cents: 3_999, day: 8 },
  ]
  for (const rejection of rejections) {
    await createTransaction(api, accountId, rejection.debit, -rejection.cents, lastMonthDay(rejection.day))
    await createTransaction(api, accountId, rejection.credit, rejection.cents, lastMonthDay(rejection.day + 1))
  }
  for (const label of rejections.flatMap((rejection) => [rejection.debit, rejection.credit])) {
    await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?transferKind=rejected&itemsPerPage=100',
      (candidate) => candidate.label === label && candidate.transferKind === 'rejected',
      { what: `The line "${label}", recognised as a rejection` },
    )
  }

  const account = new FinanceTransfersPage(page)
  await account.openTransactions(accountId)
  await account.incidentsTab(2).click()
  const orange = account.row('ORANGE')
  await expect(orange).toHaveCount(1)

  // The newest first: ORANGE. It leaves the pile, and « Annuler » puts it back.
  await account.settle('ORANGE').click()
  await expect(account.incidentsTab(1)).toBeVisible()
  await expect(account.settle('ORANGE')).toHaveCount(0)
  await expect(account.settle('EDF')).toBeVisible()
  await account.undo.click()
  await expect(account.incidentsTab(2)).toBeVisible()
  await expect(account.settle('ORANGE')).toBeVisible()
  await expect(account.settledSection(1)).toHaveCount(0)

  // Settled again and left so, it waits in the folded « Régularisés ».
  await account.settle('ORANGE').click()
  await expect(account.incidentsTab(1)).toBeVisible()
  await account.settledSection(1).click()
  await expect(account.content.getByRole('button', { name: 'Remettre dans la pile : ORANGE' })).toBeVisible()
})

/**
 * One sheet for every line (MAG-464): an ordinary line opens on its six
 * sections, marking it internal leaves Nature and Note, and taking the marking
 * off brings the others back.
 */
test('an ordinary line opens on six sections, and marking it internal keeps only Nature and Note', async ({
  transfersUser,
}) => {
  const { api, page } = transfersUser
  const attempt = test.info().retry
  const label = `ACHAT FICHE MAG-464, essai ${attempt}`
  const sections = ['nature', 'classement', 'recurrence', 'partage', 'jugement', 'note']

  const accountId = await createAccount(api, `Courant MAG-464, essai ${attempt}`, 'checking')
  createdAccountIds.push(accountId)
  await createTransaction(api, accountId, label, -4_200, lastMonthDay(14))
  await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.label === label,
    { what: `The line "${label}"` },
  )

  const account = new FinanceTransfersPage(page)
  await account.openTransactions(accountId)
  await account.openTransaction(label)
  expect(await account.sheetSections()).toEqual(sections)

  await account.content.getByRole('button', { name: 'C’est un virement interne' }).click()
  await expect(page.getByRole('dialog', { name: 'Choisir la contrepartie' })).toBeVisible()
  await page.getByRole('button', { name: 'Marquer comme virement interne' }).click()
  await expect.poll(() => account.sheetSections()).toEqual(['nature', 'note'])

  await account.release.click()
  await expect.poll(() => account.sheetSections()).toEqual(sections)
})

/**
 * A transfer is recognised whichever door its legs come through (MAG-367): here
 * two statements imported one after the other, one per account. No catch-up is
 * run — the detection follows the recording of the second leg by itself.
 */
test('the two legs of a transfer imported from statements are both marked, and leave the income', async ({
  transfersUser,
}) => {
  const { api, page } = transfersUser
  const attempt = test.info().retry
  // Unique per attempt, amount included, for the reason the first journey gives.
  const transferCents = 120_000 + attempt * 100
  const checkingName = `Courant MAG-367, essai ${attempt}`
  const savingsName = `Livret MAG-367, essai ${attempt}`
  const debit = `VIREMENT VERS COURANT MAG-367 ESSAI ${attempt}`
  const credit = `VIREMENT DEPUIS LIVRET MAG-367 ESSAI ${attempt}`
  const debitDay = lastMonthDay(12)
  const creditDay = lastMonthDay(13)
  const month = debitDay.slice(0, 7)
  const amount = (cents: number) => (cents / 100).toFixed(2).replace('.', ',')

  const checkingId = await createAccount(api, checkingName, 'checking')
  const savingsId = await createAccount(api, savingsName, 'savings')
  createdAccountIds.push(checkingId, savingsId)
  const incomeBefore = await monthIncomeCents(api, month)

  const importLeg = async (accountName: string, day: string, label: string, signedCents: number) => {
    const importPage = new FinanceImportPage(page)
    await importPage.open()
    await importPage.chooseAccount(`EUR · ${accountName}`)
    await importPage.dropStatement(
      ['Date;Libellé;Montant', `${frenchDate(day)};${label};${amount(signedCents)}`, ''].join('\n'),
    )
    await importPage.simulate.click()
    await importPage.confirm.click()
    await expect(importPage.content.getByText('Import effectué')).toBeVisible()
  }

  await importLeg(savingsName, debitDay, debit, -transferCents)
  await importLeg(checkingName, creditDay, credit, transferCents)

  for (const label of [debit, credit]) {
    await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
      (candidate) => candidate.label === label && candidate.transferKind === 'internal',
      { what: `The leg "${label}", marked by the import itself` },
    )
  }

  const transfers = new FinanceTransfersPage(page)
  await transfers.openTransactions(checkingId)
  await expect(transfers.badge(credit)).toBeVisible()
  await transfers.openTransactions(savingsId)
  await expect(transfers.badge(debit)).toBeVisible()

  expect(await monthIncomeCents(api, month), 'the credit leg is no income').toBe(incomeBefore)
})
