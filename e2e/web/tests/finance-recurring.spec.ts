import type { APIRequestContext } from '@playwright/test'
import { test, expect, parisDay } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { expectRealtimeSync, openMercureProbe, openSubscribed, userTopic } from '../helpers/mercure.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'
import { FinanceAccountsPage } from '../pages/FinanceAccountsPage.js'
import { FinanceRecurringPage } from '../pages/FinanceRecurringPage.js'

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
 * MAG-308 puts that step on the screen: the proposal is read in « Rattachements
 * proposés », « Rattacher » accepts it, and the other tab shows the new
 * reference amount through Mercure.
 *
 * Third step, MAG-306: what is read of a series — its cost, its next due date —
 * from the overview the screen and Maggie share:
 *
 *   Étant donné l'opération récurrente « Abonnement Flixo », 13,49 €, le 12 de chaque mois, son débit rattaché
 *   Quand je consulte les opérations récurrentes
 *   Alors « Abonnement Flixo » coûte 13,49 € par mois, 161,88 € par an
 *     et sa prochaine échéance est le 12 du mois à venir
 *   Quand je demande à Maggie « combien me coûtent mes abonnements ? »
 *   Alors elle a lu les opérations récurrentes (`get_recurring_operations`)
 *
 * Fourth step, MAG-308: the same series on the screen, and a series made from
 * a transaction:
 *
 *   Étant donné trois débits « PRLV NETFLIX » de 13,99 € le 8, sur trois mois consécutifs
 *   Quand, dans la liste du compte, j'en fais une opération récurrente
 *   Alors le formulaire s'ouvre prérempli (contrepartie, montant, jour, compte)
 *     et on me propose de rattacher « 3 transactions Netflix depuis … », avec leur aperçu
 *   Quand je confirme
 *   Alors les trois lignes portent le badge « Série · Netflix »
 *   Quand je détache l'une d'elles
 *   Alors elle perd son badge

 *
 * Fifth step, MAG-475: a series needs no identity, its amount and its sign are enough:
 *
 *   Étant donné un crédit « VIR SEPA XYZ » de 4 650 €, et d'autres lignes qui n'en sont pas (un crédit de 3 000 €, un débit de 4 750 €)
 *   Quand je crée la série « Salaire », recette de 4 750 € ± 10 %, sans contrepartie ni libellé
 *   Alors l'aperçu montre le crédit de 4 650 € et lui seul, avant que j'enregistre
 *     et la série est enregistrée sans contrepartie ni motif
 *   Quand je rattache l'historique
 *   Alors le crédit de 4 650 € est rattaché à la série
 */

interface StoredSeries {
  id?: string
  label?: string
  counterpartyKey?: string | null
  referenceAmountCents?: number
  monthlyCostCents?: number
  yearlyCostCents?: number
}

// An empty account made for each test (MAG-577): the catch-up of the recurring
// operations is a pass over the user's whole history, and the history it passes
// over is the test's own, so the tests need no order and leave nothing to clean.
test.use({ journeyAccount: 'empty' })

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

  const accountId = await created(api, '/api/accounts', { name: 'Courant MAG-304', type: 'checking', currency: 'EUR', balanceCents: 0 })

  const response = await api.post('/api/recurring_operations', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      label: 'Abonnement Flixo',
      counterpartyName: 'Flixo',
      account: `/api/accounts/${accountId}`,
      category: `/api/categories/${categoryId}`,
      period: 'monthly',
      anchorOn,
      referenceAmountCents: -1349,
    },
  })
  expect(response.status(), await response.text()).toBe(201)
  const id = String(((await response.json()) as StoredSeries).id ?? '')

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
  label?: string
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
  api,
  page,
  session,
  twoWindows,
}) => {
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-305, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  const categoryId = await created(api, '/api/categories', {
    name: `Abonnements MAG-305, essai ${attempt}`,
    obligation: 'optional',
  })

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
  const dearerLabel = `PAIEMENT PAR CARTE FLIXO 12/${nextDue.slice(5, 7)}`
  const dearer = await debit(api, accountId, dearerLabel, -1799, nextDue)
  expect(dearer.recurringOperation ?? null).toBeNull()

  const proposal = (await dryRun(api)).proposals.find((p) => p.transactionId === dearer.id)
  expect(proposal, 'the price rise must be proposed').toBeDefined()
  expect(proposal?.reason).toBe('amount_up')
  expect(proposal?.recurringOperationId).toBe(seriesId)
  expect(proposal?.referenceAmountCents).toBe(-1349)
  expect(proposal?.amountChangePercent).toBe(33)

  // The screen says it: the line, why it was only proposed, and the old reference.
  const recurring = new FinanceRecurringPage(page)
  const observer = new FinanceRecurringPage(twoWindows.observer)
  await openSubscribed(observer.page, () => observer.open())
  await expect(observer.row(`Courant MAG-305, essai ${attempt}`)).toContainText('13,49')

  await recurring.open()
  await expect(recurring.proposals).toContainText(dearerLabel)
  await expect(recurring.proposals).toContainText('hausse de prix 13,49 € → 17,99 €, +33 %')
  await expect(recurring.row(`Courant MAG-305, essai ${attempt}`)).toContainText('mesuré')

  // Accepted from the screen: the line is attached and the reference follows
  // the new price — in the other tab too, without a reload.
  await expectRealtimeSync(
    observer.page,
    async () => {
      await recurring.accept(dearerLabel).click()
      await expect(recurring.proposals).toHaveCount(0)
    },
    async (other) => {
      await expect(new FinanceRecurringPage(other).row(`Courant MAG-305, essai ${attempt}`)).toContainText('17,99')
    },
  )
  const acceptedLine = await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (t) => t.id === dearer.id && t.recurringOperation === seriesIri,
    { what: 'The accepted line, attached' },
  )
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

interface OverviewOperation {
  id: string
  label: string
  monthlyCostCents: number
  yearlyCostCents: number
  nextOccurrenceOn: string | null
  missedOccurrence: unknown
  priceChange: unknown
}

test('the overview reads a subscription\'s cost and next due date, and Maggie reads it too', async ({ api, page }) => {
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-306, essai ${attempt}`,
    bank: 'Crédit Agricole',
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  const categoryId = await created(api, '/api/categories', {
    name: `Abonnements MAG-306, essai ${attempt}`,
    obligation: 'optional',
  })

  // Given the series, with this month's debit attached to the 12th.
  const anchorOn = `${parisDay().slice(0, 7)}-12`
  const seriesId = await created(api, '/api/recurring_operations', {
    label: 'Abonnement Flixo',
    counterpartyName: 'Flixo',
    account: `/api/accounts/${accountId}`,
    category: `/api/categories/${categoryId}`,
    period: 'monthly',
    anchorOn,
    referenceAmountCents: -1349,
  })
  const debitLine = await debit(api, accountId, `PAIEMENT PAR CARTE FLIXO ${anchorOn.slice(8, 10)}/${anchorOn.slice(5, 7)}`, -1349, anchorOn)
  expect(debitLine.recurringOperation).toBe(`/api/recurring_operations/${seriesId}`)

  // When I read the overview.
  const response = await api.get('/api/finance/recurring-operations/overview')
  expect(response.status(), await response.text()).toBe(200)
  const overview = (await response.json()) as { operations: OverviewOperation[] }
  const flixo = overview.operations.find((operation) => operation.id === seriesId)

  // Then 13,49 € a month, 161,88 € a year, next due the 12th of the coming month.
  expect(flixo, 'the new series must be in the overview').toBeDefined()
  expect(flixo?.monthlyCostCents).toBe(-1349)
  expect(flixo?.yearlyCostCents).toBe(-16188)
  expect(flixo?.nextOccurrenceOn).toBe(nextMonth(anchorOn))
  expect(flixo?.missedOccurrence).toBeNull()
  expect(flixo?.priceChange).toBeNull()

  // And the screen reads the same: cost, next due date, and no badge it was not given.
  const recurring = new FinanceRecurringPage(page)
  await recurring.open()
  const dueDay = nextMonth(anchorOn).split('-').reverse().join('/')
  await expect(recurring.row(`Courant MAG-306, essai ${attempt}`)).toContainText('13,49 € / mois')
  await expect(recurring.row(`Courant MAG-306, essai ${attempt}`)).toContainText('161,88 € / an')
  await expect(recurring.row(`Courant MAG-306, essai ${attempt}`)).toContainText('Le 12 de chaque mois')
  await expect(recurring.row(`Courant MAG-306, essai ${attempt}`)).toContainText('Crédit Agricole')
  await expect(recurring.row(`Courant MAG-306, essai ${attempt}`)).toContainText(dueDay)
  await expect(recurring.row(`Courant MAG-306, essai ${attempt}`)).not.toContainText('Échéance manquée')
  await expect(recurring.row(`Courant MAG-306, essai ${attempt}`)).not.toContainText('Hausse de prix')

  // When I ask Maggie what my subscriptions cost.
  await new HomePage(page).open()
  const events = await new ChatPanel(page).send('Combien me coûtent mes abonnements ?')

  // Then she read the recurring operations, and it went through.
  expect(calledTools(events)).toContain('get_recurring_operations')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'get_recurring_operations', status: 'success' })]),
  )
})

/** The month `back` months before `month` (YYYY-MM). */
function monthBefore(month: string, back: number): string {
  const [year, m] = month.split('-').map(Number)
  const index = year * 12 + (m - 1) - back

  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}

test('a debit becomes a recurring operation from the account list, its history is offered and it can be detached', async ({
  api,
  page,
}) => {
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-308, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  const categoryName = `Abonnements MAG-308, essai ${attempt}`
  await created(api, '/api/categories', { name: categoryName, obligation: 'optional' })

  // Given three Netflix debits on the 8th, over the three months already past.
  const thisMonth = parisDay().slice(0, 7)
  const label = `PRLV NETFLIX MAG-308 ESSAI ${attempt}`
  for (const back of [3, 2, 1]) {
    await debit(api, accountId, label, -1399, `${monthBefore(thisMonth, back)}-08`)
  }
  await waitForIndexed<StoredTransaction>(api, '/api/transactions?itemsPerPage=100', (t) => t.label === label, {
    what: 'The Netflix debits',
  })
  const netflixLines = async () =>
    (await getCollection<StoredTransaction>(api, '/api/transactions?itemsPerPage=100')).filter((t) => t.label === label)

  // When, in the account list (latest first), I make a series of the oldest one: a series runs from its anchor on.
  const list = new FinanceAccountsPage(page)
  await list.openTransactions(accountId, { back: 3 })
  const recurring = new FinanceRecurringPage(page)
  await list.openTransaction(label, 2)
  await list.content.getByRole('button', { name: 'En faire une opération récurrente' }).click()

  // Then the form opens filled from the line.
  await expect(recurring.form.getByLabel(/Libellé/)).not.toHaveValue('')
  await expect(recurring.form.getByLabel(/Montant de référence/)).toHaveValue('13.99')
  await expect(recurring.form.getByLabel(/Date d’une échéance/)).toHaveValue(/^\d{4}-\d{2}-08$/)
  await expect(recurring.form.getByLabel('Tolérance sur le montant (%)')).toHaveValue('20')
  await expect(recurring.form.getByLabel('Tolérance sur la date (jours)')).toHaveValue('5')
  await expect(recurring.form.getByRole('combobox', { name: /Compte/ })).toContainText(`Courant MAG-308, essai ${attempt}`)
  await recurring.form.getByLabel(/Libellé/).fill('Netflix')
  await expect(recurring.form.getByLabel('Contrepartie attendue')).not.toHaveValue('')
  await recurring.choose(/Catégorie/, categoryName)
  await recurring.form.getByRole('button', { name: 'Créer la série' }).click()

  // And the history of the same payee is offered, rehearsed before it is written.
  await expect(recurring.historyPreview).toContainText('transactions Netflix depuis')
  await expect(recurring.historyPreview).toContainText(label)
  expect((await netflixLines()).filter((t) => t.recurringOperation), 'the preview writes nothing').toHaveLength(0)

  await recurring.historyPreview.getByRole('button', { name: /^Rattacher ces \d+ transaction/ }).click()

  // Then the three lines wear the badge of the series.
  await expect(recurring.historyPreview).toHaveCount(0)
  await expect.poll(async () => (await netflixLines()).filter((t) => t.recurringOperation).length).toBe(3)
  await list.openTransactions(accountId, { back: 3 })
  await expect(list.content.getByText('Série · Netflix')).toHaveCount(3)

  // When I detach one, it loses its badge, and the others keep theirs.
  await list.openTransaction(label)
  const detaching = page.waitForResponse((r) => r.request().method() === 'PATCH' && r.url().includes('/api/transactions/'))
  await list.content.getByRole('button', { name: 'Détacher de la série' }).click()
  expect((await detaching).status()).toBe(200)
  await expect.poll(async () => (await netflixLines()).filter((t) => t.recurringOperation).length).toBe(2)
  await list.openTransactions(accountId, { back: 3 })
  await expect(list.content.getByText('Série · Netflix')).toHaveCount(2)
})

test('a debit without counterparty becomes a recurring operation recognised by its label (MAG-437)', async ({ api, page }) => {
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-437, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  const categoryName = `Abonnements MAG-437, essai ${attempt}`
  await created(api, '/api/categories', { name: categoryName, obligation: 'optional' })

  // Given a debit whose label names no merchant: the bank gives it no counterparty.
  const label = 'PAIEMENT PAR CARTE X9633'
  const patternValue = label
  const line = await debit(api, accountId, label, -1250, `${parisDay().slice(0, 7)}-03`)
  expect(line.id).toBeTruthy()

  // When, in the account list, I make a recurring operation of it
  const list = new FinanceAccountsPage(page)
  await list.openTransactions(accountId)
  const recurring = new FinanceRecurringPage(page)
  await list.openTransaction(label)
  await list.content.getByRole('button', { name: 'En faire une opération récurrente' }).click()

  // Then the counterparty is empty and the label pattern is filled
  await expect(recurring.form.getByLabel('Contrepartie attendue')).toHaveValue('')
  const pattern = recurring.form.getByLabel('Motif du libellé')
  await expect(pattern).not.toHaveValue('')
  await recurring.choose(/Catégorie/, categoryName)

  // When I write the pattern myself (this label has no merchant to keep) and save
  await recurring.form.getByLabel('Motif du libellé').fill(patternValue)
  await recurring.form.getByRole('button', { name: 'Créer la série' }).click()

  // Then the series is stored with its pattern and no counterparty
  const series = await waitForIndexed<{ id?: string; label?: string; labelPattern?: string | null; counterpartyName?: string | null }>(
    api,
    '/api/recurring_operations',
    (s) => s.labelPattern === patternValue,
    { what: 'The series recognised by its label' },
  )
  expect(series.counterpartyName ?? null).toBeNull()

  // And it appears in « Opérations récurrentes » with its pattern
  await recurring.open()
  await expect(recurring.row(String(series.label))).toContainText(patternValue)
})

test('an unrolled series lists the transactions it recognised, newest first, and one can be detached', async ({
  api,
  page,
}) => {
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-438, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  const categoryId = await created(api, '/api/categories', {
    name: `Abonnements MAG-438, essai ${attempt}`,
    obligation: 'optional',
  })

  // Given a monthly series anchored last month, and its two debits attached.
  const thisMonth = parisDay().slice(0, 7)
  const anchorOn = `${monthBefore(thisMonth, 1)}-12`
  const seriesName = `Abonnement Flixo MAG-438 ${attempt}`
  await created(api, '/api/recurring_operations', {
    label: seriesName,
    counterpartyName: 'Flixo',
    account: `/api/accounts/${accountId}`,
    category: `/api/categories/${categoryId}`,
    period: 'monthly',
    anchorOn,
    referenceAmountCents: -1349,
  })
  const secondOn = nextMonth(anchorOn)
  const firstLabel = `PAIEMENT PAR CARTE FLIXO ${anchorOn.slice(8, 10)}/${anchorOn.slice(5, 7)}`
  const secondLabel = `PAIEMENT PAR CARTE FLIXO ${secondOn.slice(8, 10)}/${secondOn.slice(5, 7)}`
  const first = await debit(api, accountId, firstLabel, -1349, anchorOn)
  const second = await debit(api, accountId, secondLabel, -1349, secondOn)
  expect(first.recurringOperation).toBeTruthy()
  expect(second.recurringOperation).toBeTruthy()

  // When I unroll its row.
  const recurring = new FinanceRecurringPage(page)
  await recurring.open()
  await recurring.unrollToggle(seriesName).click()

  // Then its transactions are there, the most recent first, with the due date they cover.
  const lines = recurring.seriesTransactions().getByRole('row')
  await expect(lines).toHaveCount(3)
  await expect(lines.nth(1)).toContainText(secondLabel)
  await expect(lines.nth(1)).toContainText(secondOn.split('-').reverse().join('/'))
  await expect(lines.nth(2)).toContainText(firstLabel)

  // When I detach one, it leaves the series without a reload.
  const detaching = page.waitForResponse((r) => r.request().method() === 'PATCH' && r.url().includes('/api/transactions/'))
  await recurring.seriesTransactions().getByLabel(`Détacher ${secondLabel} de la série`).click()
  expect((await detaching).status()).toBe(200)
  await expect(lines).toHaveCount(2)
  await expect(recurring.seriesTransactions()).not.toContainText(secondLabel)
})

test('a series without counterparty nor label recognises a credit by its amount alone, previewed before it is saved (MAG-475)', async ({
  api,
  page,
}) => {
  const attempt = test.info().retry

  const accountName = `Courant MAG-475, essai ${attempt}`
  const accountId = await created(api, '/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 0 })
  const categoryName = `Salaire MAG-475, essai ${attempt}`
  await created(api, '/api/categories', { name: categoryName, obligation: 'income' })

  // Given a credit of 4 650 € whose label says nothing of an employer, and lines that are not the salary.
  const thisMonth = parisDay().slice(0, 7)
  const day = `${monthBefore(thisMonth, 1)}-02`
  const label = `VIR SEPA XYZ MAG-475 ESSAI ${attempt}`
  const salary = await debit(api, accountId, label, 465000, day)
  await debit(api, accountId, `VIR SEPA AUTRE MAG-475 ESSAI ${attempt}`, 300000, day)
  await debit(api, accountId, `PRLV GROS LOYER MAG-475 ESSAI ${attempt}`, -475000, day)
  await waitForIndexed<StoredTransaction>(api, '/api/transactions?itemsPerPage=100', (t) => t.label === label, {
    what: 'The credit of 4 650 €',
  })

  // When I create the series « Salaire »: a recette of 4 750 € ± 10 %, neither counterparty nor label.
  const recurring = new FinanceRecurringPage(page)
  await recurring.open()
  await page.getByRole('button', { name: 'Nouvelle opération récurrente' }).click()
  const seriesName = `Salaire MAG-475 ${attempt}`
  await recurring.form.getByLabel(/Libellé/).fill(seriesName)
  await recurring.form.getByRole('button', { name: 'Recette' }).click()
  await recurring.form.getByLabel(/Montant de référence/).fill('4750')
  await recurring.form.getByLabel('Tolérance sur le montant (%)').fill('10')
  await recurring.choose(/Compte/, `EUR · ${accountName}`)
  await recurring.choose(/Catégorie/, categoryName)
  await recurring.form.getByLabel(/Date d’une échéance/).fill(day)

  // Then the preview lists the credit and only it, before anything is saved.
  const matches = recurring.form.getByRole('region', { name: 'Transactions reconnues' })
  await expect(matches).toContainText('1 transaction(s) reconnue(s)')
  await expect(matches).toContainText(label)
  await expect(matches).not.toContainText('VIR SEPA AUTRE')
  await expect(matches).not.toContainText('PRLV GROS LOYER')
  expect(
    (await getCollection<StoredSeries>(api, '/api/recurring_operations')).filter((s) => s.label === seriesName),
    'the preview writes nothing',
  ).toHaveLength(0)

  // When I save, the series is stored without counterparty nor label pattern.
  await recurring.form.getByRole('button', { name: 'Créer la série' }).click()
  const series = await waitForIndexed<{ label?: string; labelPattern?: string | null; counterpartyName?: string | null }>(
    api,
    '/api/recurring_operations',
    (s) => s.label === seriesName,
    { what: 'The series recognised by its amount' },
  )
  expect(series.counterpartyName ?? null).toBeNull()
  expect(series.labelPattern ?? null).toBeNull()

  // When I attach the history, the credit is rattaché to the series.
  await recurring.open()
  await recurring.catchUp.click()
  await expect(recurring.preview).toContainText(label)
  await recurring.preview.getByRole('button', { name: /^Rattacher ces \d+ transaction/ }).click()
  await expect(recurring.preview).toHaveCount(0)
  await expect
    .poll(async () => {
      const lines = await getCollection<StoredTransaction>(api, '/api/transactions?itemsPerPage=100')

      return lines.find((t) => t.id === salary.id)?.recurringOperation ?? null
    })
    .not.toBeNull()
})

/**
 * Qualification on the series and the sheet (MAG-479):
 *
 *   Étant donné un débit « PRLV CREDIT AUTO » de 250 €
 *   Quand j'en fais une série « Crédit auto », obligatoire, de type remboursement
 *     et que je rattache l'historique
 *   Alors la fiche de la transaction indique Remboursement, Obligatoire et « posée par la série Crédit auto »
 *   Quand la série devient non obligatoire
 *   Alors la transaction suit
 *   Quand je qualifie cette ligne à la main
 *   Alors sa fiche dit « à la main » et un changement de la série n'y touche plus
 */
test('a series qualifies its transactions, a hand qualification stays put (MAG-479)', async ({ api, page }) => {
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-479, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  const categoryName = `Crédits MAG-479, essai ${attempt}`
  await created(api, '/api/categories', { name: categoryName })

  // Given a monthly debit.
  const thisMonth = parisDay().slice(0, 7)
  const label = `PRLV CREDIT AUTO MAG-479 ESSAI ${attempt}`
  await debit(api, accountId, label, -25000, `${monthBefore(thisMonth, 1)}-05`)

  // When I make a series of it, mandatory, of type repayment (every category is offered).
  const list = new FinanceAccountsPage(page)
  await list.openTransactions(accountId, { back: 1 })
  const recurring = new FinanceRecurringPage(page)
  await list.openTransaction(label)
  await list.content.getByRole('button', { name: 'En faire une opération récurrente' }).click()
  await recurring.form.getByLabel(/Libellé/).fill('Crédit auto')
  await recurring.choose(/Catégorie/, categoryName)
  await recurring.form.getByRole('switch', { name: 'Obligatoire' }).check()
  await recurring.form.getByRole('combobox', { name: 'Type' }).click()
  await page.getByRole('option', { name: 'Remboursement', exact: true }).click()
  await recurring.form.getByRole('button', { name: 'Créer la série' }).click()
  await recurring.historyPreview.getByRole('button', { name: /^Rattacher ces \d+ transaction/ }).click()
  await expect(recurring.historyPreview).toHaveCount(0)

  // Then the sheet of the line says what the series made of it.
  const sheet = async (lineLabel: string) => {
    await list.openTransactions(accountId, { back: 1 })
    await list.openTransaction(lineLabel)

    return list.content.getByTestId('qualification')
  }
  let qualification = await sheet(label)
  await expect(qualification.getByRole('switch', { name: 'Obligatoire' })).toBeChecked()
  await expect(qualification.getByRole('combobox', { name: 'Type' })).toContainText('Remboursement')
  await expect(list.content.getByTestId('qualification-provenance')).toContainText('posée par la série Crédit auto')

  // When the series becomes optional, the line follows.
  const series = await waitForIndexed<{ id?: string; label?: string }>(
    api,
    '/api/recurring_operations',
    (s) => s.label === 'Crédit auto',
    { what: 'The Crédit auto series' },
  )
  const patched = await api.patch(`/api/recurring_operations/${series.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { qualification: { mandatory: false, kind: 'repayment' } },
  })
  expect(patched.status(), await patched.text()).toBe(200)
  qualification = await sheet(label)
  await expect(qualification.getByRole('switch', { name: 'Obligatoire' })).not.toBeChecked()
  await expect(qualification.getByRole('combobox', { name: 'Type' })).toContainText('Remboursement')

  // When I qualify that same line by hand, its sheet says so.
  qualification = await sheet(label)
  await qualification.getByRole('switch', { name: 'Obligatoire' }).check()
  const saving = page.waitForResponse((r) => r.request().method() === 'PATCH' && r.url().includes('/api/transactions/'))
  await list.content.getByRole('button', { name: 'Enregistrer' }).click()
  expect((await saving).status()).toBe(200)
  qualification = await sheet(label)
  await expect(qualification.getByRole('switch', { name: 'Obligatoire' })).toBeChecked()
  await expect(list.content.getByTestId('qualification-provenance')).toContainText('à la main')

  // And a later change of the series does not touch it.
  const again = await api.patch(`/api/recurring_operations/${series.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { qualification: { mandatory: false, kind: 'saving' } },
  })
  expect(again.status(), await again.text()).toBe(200)
  const line = (await getCollection<StoredTransaction>(api, '/api/transactions?itemsPerPage=100')).find((t) => t.label === label)
  const stored = await api.get(`/api/transactions/${line?.id}`)
  expect(await stored.json()).toMatchObject({ qualifiedBy: 'manual', qualification: { mandatory: true, kind: 'repayment' } })
})

test('two accounts with the same name are told apart by their bank in the account selector (MAG-501)', async ({
  api,
  page,
}) => {
  const attempt = test.info().retry

  // Given two « Courant » accounts, one per bank.
  const accountName = `Courant MAG-501, essai ${attempt}`
  for (const bank of ['Boursorama', 'Crédit Agricole']) {
    await created(api, '/api/accounts', { name: accountName, bank, type: 'checking', currency: 'EUR', balanceCents: 0 })
  }

  // When I open the account selector of a new recurring operation.
  const recurring = new FinanceRecurringPage(page)
  await recurring.open()
  await page.getByRole('button', { name: 'Nouvelle opération récurrente' }).click()
  await recurring.form.getByRole('combobox', { name: /Compte/ }).click()

  // Then each option reads « Banque · devise · nom ».
  await expect(page.getByRole('option', { name: `Boursorama · EUR · ${accountName}`, exact: true })).toBeVisible()
  await expect(page.getByRole('option', { name: `Crédit Agricole · EUR · ${accountName}`, exact: true })).toBeVisible()
})
