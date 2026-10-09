import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedId, parisDay } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { openMercureProbe, userTopic } from '../helpers/mercure.js'

/**
 * Recurring operations (MAG-102, opérations récurrentes).
 *
 * First step, MAG-304: a series is created and reads back with what it costs a
 * month and a year. It is written through the API the admin screen will use —
 * the screen itself, and the next due date shown on it, come with MAG-308 and
 * MAG-306, which extend this file:
 *
 *   Étant donné un compte « Courant » et la catégorie « Abonnements »
 *   Quand je crée l'opération récurrente « Abonnement Flixo », 13,49 €, le 12 de chaque mois
 *   Alors elle apparaît avec 13,49 € par mois, 161,88 € par an
 *     et sa prochaine échéance est le 12 du mois à venir   ← MAG-306 / MAG-308
 *
 * Second step, MAG-305: the real transactions attach to an occurrence —
 * automatically when the counterparty, the date and the amount all hold, as a
 * proposal when one tolerance breaks:
 *
 *   Étant donné l'opération récurrente « Abonnement Flixo », 13,49 €, le 12 de chaque mois, tolérance 20 % et 5 jours
 *   Quand je saisis un débit « PAIEMENT PAR CARTE FLIXO 14/10 » de 13,49 € le 14
 *   Alors la ligne est rattachée à l'échéance du 12, catégorie « Abonnements », provenance « série »
 *     et l'autre onglet reçoit la mise à jour par Mercure
 *   Quand je saisis un débit « PAIEMENT PAR CARTE FLIXO 12/11 » de 17,99 € le 12 du mois suivant
 *   Alors la ligne n'est pas rattachée, et un rattachement est proposé « hausse de prix 13,49 € → 17,99 €, +33 % »
 *   Quand j'accepte la proposition
 *   Alors la ligne est rattachée et le montant de référence passe à 17,99 €
 *   Et un second débit Flixo le même mois reste ponctuel
 *
 * The badge on the line and the « accepter » button come with the screens
 * (MAG-308); the journey goes through the API they will call.
 */

interface StoredSeries {
  id?: string
  label?: string
  counterpartyKey?: string | null
  referenceAmountCents?: number
  monthlyCostCents?: number
  yearlyCostCents?: number
}

/**
 * Written as the neighbour, on an account of its own made for this attempt:
 * the owner's figures belong to other journeys, and the owner already holds
 * MAG-304's Flixo series — a second one on the same payee would make every
 * Flixo line ambiguous. Deleting the account takes the series and the lines
 * with it.
 */
const createdAccountIds: string[] = []
const createdCategoryIds: string[] = []

test.afterEach(async ({ otherUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await otherUser.api.delete(`/api/accounts/${id}`)
  }
  for (const id of createdCategoryIds.splice(0)) {
    await otherUser.api.delete(`/api/categories/${id}`)
  }
})

test('a monthly subscription reads back with its monthly and yearly cost', async ({ api }) => {
  // A category of its own: the seeded ones carry other journeys' figures.
  const category = await api.post('/api/categories', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { name: 'Abonnements MAG-304', obligation: 'optional' },
  })
  expect(category.status(), await category.text()).toBe(201)
  const categoryId = String(((await category.json()) as { id?: string }).id ?? '')

  // Anchored on the 12th of the current month, whatever day the run falls on.
  const anchorOn = `${parisDay().slice(0, 7)}-12`

  const created = await api.post('/api/recurring_operations', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      label: 'Abonnement Flixo',
      counterpartyName: 'Flixo',
      account: `/api/accounts/${seedId('e2e_account_checking')}`,
      category: `/api/categories/${categoryId}`,
      period: 'monthly',
      anchorOn,
      referenceAmountCents: -1349,
    },
  })
  expect(created.status(), await created.text()).toBe(201)
  const id = String(((await created.json()) as StoredSeries).id ?? '')

  const listed = await waitForIndexed<StoredSeries>(api, '/api/recurring_operations', (s) => s.id === id, {
    what: 'The new recurring operation',
  })

  expect(listed.label).toBe('Abonnement Flixo')
  expect(listed.counterpartyKey).toBe('flixo')
  // 13,49 € a month, 161,88 € a year — negative: it is an expense.
  expect(listed.monthlyCostCents).toBe(-1349)
  expect(listed.yearlyCostCents).toBe(-16188)
})

interface StoredTransaction {
  id?: string
  recurringOperation?: string | null
  recurringOccurrenceOn?: string | null
  recurringSource?: string
  categorySource?: string
  category?: string | null
}

interface Proposal {
  transactionId: string
  reason: string
  recurringOperationId?: string
  occurrenceOn?: string
  referenceAmountCents?: number
  amountChangePercent?: number
}

interface CatchUp {
  attachments: Array<{ transactionId: string }>
  proposals: Proposal[]
}

/** A month after `day` (YYYY-MM-DD), same day of the month. */
function nextMonth(day: string): string {
  const [year, month] = day.split('-').map(Number)
  const [y, m] = month === 12 ? [year + 1, 1] : [year, month + 1]

  return `${y}-${String(m).padStart(2, '0')}-${day.slice(8, 10)}`
}

async function created(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<string> {
  const response = await api.post(path, { headers: { 'Content-Type': 'application/ld+json' }, data })
  expect(response.status(), await response.text()).toBe(201)

  return String(((await response.json()) as { id?: string }).id ?? '')
}

async function debit(
  api: APIRequestContext,
  accountId: string,
  label: string,
  amountCents: number,
  bookedAt: string,
): Promise<StoredTransaction> {
  const response = await api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { account: `/api/accounts/${accountId}`, label, amountCents, currency: 'EUR', bookedAt, status: 'spent' },
  })
  expect(response.status(), await response.text()).toBe(201)

  return (await response.json()) as StoredTransaction
}

/** What the catch-up would do, without doing it: where a proposal is read. */
async function dryRun(api: APIRequestContext): Promise<CatchUp> {
  const response = await api.post('/api/finance/recurring-operations/attach', {
    headers: { 'Content-Type': 'application/json' },
    data: { dryRun: true },
  })
  expect(response.status(), await response.text()).toBe(200)

  return (await response.json()) as CatchUp
}

test('a Flixo debit attaches to its occurrence, a price rise is proposed, and accepting it moves the reference', async ({
  otherUser,
}) => {
  const { api, page, session } = otherUser
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-305, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  createdAccountIds.push(accountId)
  const categoryId = await created(api, '/api/categories', {
    name: `Abonnements MAG-305, essai ${attempt}`,
    obligation: 'optional',
  })
  createdCategoryIds.push(categoryId)

  const anchorOn = `${parisDay().slice(0, 7)}-12`
  const seriesId = await created(api, '/api/recurring_operations', {
    label: 'Abonnement Flixo',
    counterpartyName: 'Flixo',
    account: `/api/accounts/${accountId}`,
    category: `/api/categories/${categoryId}`,
    period: 'monthly',
    anchorOn,
    referenceAmountCents: -1349,
    amountTolerancePercent: 20,
    dateToleranceDays: 5,
  })
  const seriesIri = `/api/recurring_operations/${seriesId}`

  // The other tab: the neighbour's own topics, its own subscriber token.
  await page.goto('/')
  const probe = await openMercureProbe(page, [userTopic(session.user.id, '/api/transactions/{id}')])

  // 13,49 € two days after the due date: attached as it lands.
  const day14 = `${anchorOn.slice(0, 8)}14`
  const onTime = await debit(api, accountId, `PAIEMENT PAR CARTE FLIXO ${day14.slice(8, 10)}/${day14.slice(5, 7)}`, -1349, day14)
  expect(onTime.recurringOperation).toBe(seriesIri)
  expect(onTime.recurringOccurrenceOn?.slice(0, 10)).toBe(anchorOn)
  expect(onTime.recurringSource).toBe('auto')
  expect(onTime.categorySource).toBe('series')
  expect(onTime.category).toBe(`/api/categories/${categoryId}`)

  const update = await probe.waitFor((message) => message.parsed?.recurringOperationId === seriesId)
  expect(update.parsed?.categorySource).toBe('series')
  await probe.close()

  // 17,99 € on the next due date: a price rise, proposed, nothing written.
  const nextDue = nextMonth(anchorOn)
  const dearer = await debit(api, accountId, `PAIEMENT PAR CARTE FLIXO 12/${nextDue.slice(5, 7)}`, -1799, nextDue)
  expect(dearer.recurringOperation ?? null).toBeNull()

  const proposal = (await dryRun(api)).proposals.find((p) => p.transactionId === dearer.id)
  expect(proposal, 'the price rise must be proposed').toBeDefined()
  expect(proposal?.reason).toBe('amount_up')
  expect(proposal?.recurringOperationId).toBe(seriesId)
  expect(proposal?.referenceAmountCents).toBe(-1349)
  expect(proposal?.amountChangePercent).toBe(33)

  // Accepted: attached by hand, and the reference follows the new price.
  const accepted = await api.patch(`/api/transactions/${dearer.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { recurringOperation: seriesIri },
  })
  expect(accepted.status(), await accepted.text()).toBe(200)
  const acceptedLine = (await accepted.json()) as StoredTransaction
  expect(acceptedLine.recurringOccurrenceOn?.slice(0, 10)).toBe(nextDue)
  expect(acceptedLine.recurringSource).toBe('manual')

  const series = await waitForIndexed<StoredSeries & { id?: string }>(
    api,
    '/api/recurring_operations',
    (s) => s.id === seriesId && s.referenceAmountCents === -1799,
    { what: 'The recalibrated reference amount' },
  )
  expect(series.referenceAmountCents).toBe(-1799)

  // A second Flixo debit the same month: the occurrence is settled, a one-off.
  const twice = await debit(api, accountId, `PAIEMENT PAR CARTE FLIXO 13/${nextDue.slice(5, 7)}`, -1799, `${nextDue.slice(0, 8)}13`)
  expect(twice.recurringOperation ?? null).toBeNull()
  const after = await dryRun(api)
  expect(after.proposals.find((p) => p.transactionId === twice.id)).toBeUndefined()
  expect(after.attachments.find((a) => a.transactionId === twice.id)).toBeUndefined()
})
