import type { APIRequestContext } from '@playwright/test'
import { test, expect, parisDay } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { waitForIndexed } from '../helpers/api.js'
import { euros } from '../helpers/money.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'
import { FinanceTreasuryPage } from '../pages/FinanceTreasuryPage.js'
import { ROUTES, adminUrl } from '../pages/routes.js'

/**
 * Les avances d'épargne (MAG-276, MAG-277, extends MAG-102).
 *
 *   Étant donné un salaire attendu le jour J et un repère de 4 950,00 €
 *     et aucun crédit de salaire depuis le jour J du mois dernier (30 jours dans un mois de 30)
 *     et un virement Livret → Courant de 1 500,00 € pendant le retard
 *     et un Courant qui contient 4 000,00 €
 *   Quand je demande à Maggie « combien je dois remettre sur mon livret ? »
 *   Alors `get_savings_advances` et `propose_advance_repayments` ont tourné et ont réussi
 *     et l'API rend une avance en cours de 1 500,00 € sur le Livret
 *     et un retard de salaire compté depuis le jour J du mois dernier
 *     et un contre-virement proposé de 1 500,00 € du Courant vers le Livret
 *   Quand je saisis ce contre-virement de 1 500,00 €
 *   Alors l'avance est soldée : il reste 0,00 € à rendre et plus rien n'est proposé
 *   Quand je saisis ensuite un approvisionnement de 200,00 € du Courant vers le Livret
 *   Alors les deux lignes restent un virement interne neutre
 *     et l'avance soldée n'a rien reçu de plus
 *
 * Second journey (MAG-278, reshaped by MAG-632): Given the same advance, when I open the admin
 * screen « Avances d'épargne », then 1 500,00 € are left to give back, on the Livret's row;
 * when I click « Remettre », then the advance is settled.
 *
 * Third journey (MAG-632): the treasury is one page, with no tabs — curve, cushion, advances,
 * créances et dettes, movements — and its settings sit behind « Régler ».
 *
 * The expected day is today's, so the current month is not due yet and the
 * previous one is the whole delay, whatever the day the suite runs. The two
 * legs are posted like a bank sync would: the second one arriving pairs them,
 * and the detection marks the pair an advance on the spot. What the tool
 * returns is `GetSavingsAdvancesToolTest`'s business; the fake does not read it.
 *
 * Signs in as the treasury account, whose salary day and « Salaire » series no
 * other journey reads; its accounts are deleted afterwards, and its day forgotten.
 * `retries: 0` because a retry would start on what the first attempt left.
 */

// Serial: both tests lay accounts on the same user, and the outlook adds every balance up — in
// parallel the outlook read 388 000 instead of -12 000 (MAG-443's race again).
test.describe.configure({ mode: 'serial', retries: 0 })

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface StoredCategory {
  id?: string
  standardKey?: string | null
}

interface StoredTransaction {
  label?: string
  transferKind?: string
}

interface SavingsAdvances {
  advances: Array<{
    id: string
    sourceAccountName: string
    amountCents: number
    repaidCents: number
    outstandingCents: number
    isClosed: boolean
  }>
  proposals: Array<{ fromAccountId: string; toAccountId: string; amountCents: number; advanceId: string }>
  proposalsTotalCents: number
  bySource: Array<{ sourceAccountName: string; outstandingCents: number }>
  totalOutstandingCents: number
  salaryDelay: {
    isConfigured: boolean
    isLate: boolean
    delayDays: number
    shortfallCents: number
    months: Array<{ expectedOn: string; isDue: boolean }>
  }
}

interface TreasuryOutlook {
  salary: { isConfigured: boolean; isLate: boolean; delayDays: number; expectedOn: string | null }
  balanceCents: number
  projection: Array<{ date: string; balanceCents: number; movements: Array<{ label: string; amountCents: number }> }>
  overdraft: { isForecast: boolean; onDate: string | null; worstBalanceCents: number; limitCents: number }
  advice: { amountCents: number; sourceAccountId: string | null; sourceAccountName: string | null; isCushion: boolean } | null
}

const createdAccountIds: string[] = []

/** `YYYY-MM-DD` shifted by whole days, on the calendar alone. */
function shiftDay(day: string, days: number): string {
  const date = new Date(`${day}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)

  return date.toISOString().slice(0, 10)
}

/** `YYYY-MM-DD` of `day` plus whole months, then plus `days`, formatted as the table does (« 12 oct. »). */
function addMonths(day: string, months: number, days = 0): string {
  const date = new Date(`${day}T00:00:00Z`)
  date.setUTCMonth(date.getUTCMonth() + months)
  date.setUTCDate(date.getUTCDate() + days)
  const names = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.']

  return `${date.getUTCDate()} ${names[date.getUTCMonth()]}`
}

function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000)
}

async function account(api: APIRequestContext, name: string, type: string, balanceCents = 0): Promise<string> {
  const response = await api.post('/api/accounts', {
    headers: LD,
    data: { name, type, currency: 'EUR', balanceCents },
  })
  expect(response.status(), await response.text()).toBe(201)
  const id = String(((await response.json()) as { id: string }).id)
  createdAccountIds.push(id)

  return id
}

async function line(
  api: APIRequestContext,
  accountId: string,
  amountCents: number,
  bookedAt: string,
  label: string,
  status = 'spent',
): Promise<void> {
  const response = await api.post('/api/transactions', {
    headers: LD,
    data: { account: `/api/accounts/${accountId}`, label, amountCents, currency: 'EUR', bookedAt, status },
  })
  expect(response.status(), await response.text()).toBe(201)
}

async function savingsAdvances(api: APIRequestContext): Promise<SavingsAdvances> {
  const response = await api.get('/api/finance/savings-advances')
  expect(response.status(), await response.text()).toBe(200)

  return (await response.json()) as SavingsAdvances
}

/** A salary expected today (4 950 € a month), none since last month's day, and 1 500 € moved from the Livret to the Courant two days ago. */
async function givenAdvanceTaken(api: APIRequestContext, today: string): Promise<{ checkingId: string; livretId: string }> {
  // Given a salary expected today, 4 950 € a month by its « Salaire » series.
  const installed = await api.post('/api/finance/categories/standard')
  expect(installed.status(), await installed.text()).toBe(200)
  const salary = await waitForIndexed<StoredCategory>(api, '/api/categories', (c) => c.standardKey === 'salary', {
    what: 'The standard « Salaire » category',
  })
  const checkingId = await account(api, 'Courant MAG-276', 'checking', 400000)
  const livretId = await account(api, 'Livret MAG-276', 'savings')
  const series = await api.post('/api/recurring_operations', {
    headers: LD,
    data: {
      label: 'Salaire ACME MAG-276',
      counterpartyName: 'ACME MAG-276',
      account: `/api/accounts/${checkingId}`,
      category: `/api/categories/${salary.id}`,
      period: 'monthly',
      anchorOn: '2025-01-25',
      referenceAmountCents: 495000,
      referenceSource: 'declared',
    },
  })
  expect(series.status(), await series.text()).toBe(201)
  const settings = await api.patch('/api/finance/treasury-settings', {
    data: { salaryExpectedDay: Number(today.slice(8, 10)) },
  })
  expect(settings.status(), await settings.text()).toBe(200)

  // And no salary since last month's day, and 1 500 € moved from the Livret to the Courant two days ago.
  const movedOn = shiftDay(today, -2)
  await line(api, livretId, -150000, movedOn, 'VIR VERS COURANT MAG-276')
  await line(api, checkingId, 150000, movedOn, 'VIR DEPUIS LIVRET MAG-276')

  return { checkingId, livretId }
}

test.afterEach(async ({ treasuryUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await treasuryUser.api.delete(`/api/accounts/${id}`)
  }
  await treasuryUser.api.patch('/api/finance/treasury-settings', { data: { salaryExpectedDay: null, overdraftLimitCents: 0 } })
})

test('« combien je dois remettre sur mon livret ? » reads the advance taken during the delay', async ({ treasuryUser }) => {
  const { api, page } = treasuryUser
  const today = parisDay()

  // Given a salary expected today, no salary since last month's day, and 1 500 € moved from the Livret to the Courant two days ago.
  const { checkingId, livretId } = await givenAdvanceTaken(api, today)

  // When I ask Maggie how much to put back on my Livret.
  await new HomePage(page).open()
  const events = await new ChatPanel(page).send('Combien je dois remettre sur mon livret ?')

  // Then she read the savings advances and asked what to transfer, and both went through.
  expect(calledTools(events)).toEqual(expect.arrayContaining(['get_savings_advances', 'propose_advance_repayments']))
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ toolName: 'get_savings_advances', status: 'success' }),
      expect.objectContaining({ toolName: 'propose_advance_repayments', status: 'success' }),
    ]),
  )

  // And the API gives an advance of 1 500 € still owed to the Livret.
  const data = await savingsAdvances(api)
  expect(data.advances).toEqual([
    expect.objectContaining({ sourceAccountName: 'Livret MAG-276', amountCents: 150000, outstandingCents: 150000, isClosed: false }),
  ])
  expect(data.bySource).toEqual([expect.objectContaining({ sourceAccountName: 'Livret MAG-276', outstandingCents: 150000 })])
  expect(data.totalOutstandingCents).toBe(150000)

  // And a salary delay counted from last month's expected day — 30 days in a 30-day month.
  const [previous, current] = data.salaryDelay.months
  expect(data.salaryDelay.isConfigured).toBe(true)
  expect(data.salaryDelay.isLate).toBe(true)
  expect(current.isDue).toBe(false)
  expect(data.salaryDelay.delayDays).toBe(daysBetween(previous.expectedOn, today))
  expect(data.salaryDelay.delayDays).toBeGreaterThanOrEqual(28)
  expect(data.salaryDelay.shortfallCents).toBe(495000)

  // And the exact counter-transfer is proposed: 1 500 € from the Courant to the Livret.
  expect(data.proposals).toEqual([
    expect.objectContaining({ fromAccountId: checkingId, toAccountId: livretId, amountCents: 150000, advanceId: data.advances[0].id }),
  ])
  expect(data.proposalsTotalCents).toBe(150000)

  // When I make that transfer, the Courant leg first, as a bank sync would deliver it.
  await line(api, checkingId, -150000, today, 'VIR VERS LIVRET MAG-277')
  await line(api, livretId, 150000, today, 'VIR DEPUIS COURANT MAG-277')

  // Then the advance is settled on its own: nothing is left to give back, nothing is proposed.
  const settled = await savingsAdvances(api)
  expect(settled.advances).toEqual([
    expect.objectContaining({ sourceAccountName: 'Livret MAG-276', repaidCents: 150000, outstandingCents: 0, isClosed: true }),
  ])
  expect(settled.totalOutstandingCents).toBe(0)
  expect(settled.proposals).toEqual([])

  // And a 200 € top-up of the Livret afterwards is a neutral internal transfer, not a repayment.
  await line(api, checkingId, -20000, today, 'VIR APPRO LIVRET MAG-277')
  await line(api, livretId, 20000, today, 'VIR APPRO DEPUIS COURANT MAG-277')
  for (const label of ['VIR APPRO LIVRET MAG-277', 'VIR APPRO DEPUIS COURANT MAG-277']) {
    await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
      (candidate) => candidate.label === label && candidate.transferKind === 'internal',
      { what: `The top-up leg "${label}"` },
    )
  }
  const afterTopUp = await savingsAdvances(api)
  expect(afterTopUp.advances).toEqual([expect.objectContaining({ repaidCents: 150000, outstandingCents: 0, isClosed: true })])
  expect(afterTopUp.totalOutstandingCents).toBe(0)
})

/**
 * La vue trésorerie (MAG-279) — the screen is MAG-280's, below:
 *
 *   Étant donné un salaire attendu le jour J et un repère de 4 950,00 €
 *     et aucun crédit de salaire depuis le jour J du mois dernier
 *     et un Courant à -120,00 € et un Livret à 6 000,00 €
 *     et un loyer de 300,00 € prévu aujourd'hui
 *   Quand je demande la vue trésorerie
 *   Alors elle annonce le retard en jours
 *     et un découvert prévu avec sa date
 *     et une avance conseillée de 420,00 € sur le Livret
 *   Quand je déclare un découvert autorisé de 500,00 €
 *   Alors plus aucun découvert n'est annoncé, ni aucune avance conseillée
 */
test('the treasury outlook announces the delay, the overdraft and the advance to make', async ({ treasuryUser }) => {
  const { api } = treasuryUser
  const today = parisDay()

  const installed = await api.post('/api/finance/categories/standard')
  expect(installed.status(), await installed.text()).toBe(200)
  const salary = await waitForIndexed<StoredCategory>(api, '/api/categories', (c) => c.standardKey === 'salary', {
    what: 'The standard « Salaire » category',
  })
  const checkingId = await account(api, 'Courant MAG-279', 'checking', -12000)
  const livretId = await account(api, 'Livret MAG-279', 'savings', 600000)
  const series = await api.post('/api/recurring_operations', {
    headers: LD,
    data: {
      label: 'Salaire ACME MAG-279',
      counterpartyName: 'ACME MAG-279',
      account: `/api/accounts/${checkingId}`,
      category: `/api/categories/${salary.id}`,
      period: 'monthly',
      anchorOn: '2025-01-25',
      referenceAmountCents: 495000,
      referenceSource: 'declared',
    },
  })
  expect(series.status(), await series.text()).toBe(201)
  const settings = await api.patch('/api/finance/treasury-settings', {
    data: { salaryExpectedDay: Number(today.slice(8, 10)), overdraftLimitCents: 0 },
  })
  expect(settings.status(), await settings.text()).toBe(200)
  await line(api, checkingId, -30000, today, 'LOYER PREVU MAG-279', 'planned')

  // When I ask for the treasury outlook.
  const response = await api.get('/api/finance/treasury-outlook')
  expect(response.status(), await response.text()).toBe(200)
  const outlook = (await response.json()) as TreasuryOutlook

  // Then the delay is counted from last month's expected day.
  expect(outlook.salary.isConfigured).toBe(true)
  expect(outlook.salary.isLate).toBe(true)
  expect(outlook.salary.delayDays).toBe(daysBetween(outlook.salary.expectedOn ?? today, today))
  expect(outlook.salary.delayDays).toBeGreaterThanOrEqual(28)
  expect(outlook.balanceCents).toBe(-12000)

  // And an overdraft is forecast, with its date, the planned rent in the projection.
  expect(outlook.projection[0]).toEqual(
    expect.objectContaining({
      date: today,
      balanceCents: -42000,
      movements: [expect.objectContaining({ label: 'LOYER PREVU MAG-279', amountCents: -30000 })],
    }),
  )
  expect(outlook.overdraft).toEqual(expect.objectContaining({ isForecast: true, onDate: today, worstBalanceCents: -42000 }))

  // And the advance advised is on the Livret, what keeps the Courant at zero.
  expect(outlook.advice).toEqual(
    expect.objectContaining({ amountCents: 42000, sourceAccountId: livretId, sourceAccountName: 'Livret MAG-279', isCushion: false }),
  )

  // When the bank allows a 500 € overdraft, nothing is announced any more.
  const limited = await api.patch('/api/finance/treasury-settings', { data: { overdraftLimitCents: 50000 } })
  expect(limited.status(), await limited.text()).toBe(200)
  const within = (await (await api.get('/api/finance/treasury-outlook')).json()) as TreasuryOutlook
  expect(within.overdraft).toEqual(expect.objectContaining({ isForecast: false, limitCents: 50000 }))
  expect(within.advice).toBeNull()
})

/**
 * L'écran trésorerie (MAG-280, extends MAG-102).
 *
 *   Étant donné un salaire attendu le jour J et un repère de 4 950,00 €
 *     et aucun crédit de salaire depuis le jour J du mois dernier
 *     et un Courant à -120,00 € et un Livret à 6 000,00 €
 *     et un loyer de 300,00 € prévu aujourd'hui
 *   Quand j'ouvre Finance → Trésorerie
 *   Alors « Salaire en retard de N jours » est annoncé en tête
 *     et le découvert prévu a sa date, et la zone de découvert est tracée
 *     et l'avance conseillée est de 420,00 € depuis le Livret
 *   Quand je clique « Préparer l'avance »
 *   Alors le formulaire d'une transaction s'ouvre prérempli, et rien n'est encore saisi
 *   Quand je déclare un découvert autorisé de 500,00 €
 *   Alors plus aucun découvert ni aucune avance n'est annoncé
 *   Quand je choisis l'horizon « 1 an » (MAG-506)
 *   Alors la courbe du solde court sur un an, avec les courbes cumulées des entrées et des sorties
 *     et le survol d'un jour donne sa date, ses entrées et sorties cumulées et ses mouvements
 *     et aucun verdict ni chiffre de synthèse n'est affiché
 */
test('the treasury screen announces the delay, the overdraft and the advance, and prefills it without making it', async ({
  treasuryUser,
}) => {
  const { api, page } = treasuryUser
  const today = parisDay()

  const installed = await api.post('/api/finance/categories/standard')
  expect(installed.status(), await installed.text()).toBe(200)
  const salary = await waitForIndexed<StoredCategory>(api, '/api/categories', (c) => c.standardKey === 'salary', {
    what: 'The standard « Salaire » category',
  })
  const checkingId = await account(api, 'Courant MAG-280', 'checking', -12000)
  await account(api, 'Livret MAG-280', 'savings', 600000)
  const series = await api.post('/api/recurring_operations', {
    headers: LD,
    data: {
      label: 'Salaire ACME MAG-280',
      counterpartyName: 'ACME MAG-280',
      account: `/api/accounts/${checkingId}`,
      category: `/api/categories/${salary.id}`,
      period: 'monthly',
      anchorOn: '2025-01-25',
      referenceAmountCents: 495000,
      referenceSource: 'declared',
    },
  })
  expect(series.status(), await series.text()).toBe(201)
  const settings = await api.patch('/api/finance/treasury-settings', {
    data: { salaryExpectedDay: Number(today.slice(8, 10)), overdraftLimitCents: 0 },
  })
  expect(settings.status(), await settings.text()).toBe(200)
  await line(api, checkingId, -30000, today, 'LOYER PREVU MAG-280', 'planned')

  // When I open the treasury screen.
  const treasury = new FinanceTreasuryPage(page)
  await treasury.open()

  // Then the delay leads the page, counted from last month's expected day.
  const outlook = (await (await api.get('/api/finance/treasury-outlook')).json()) as TreasuryOutlook
  await expect(treasury.delay).toContainText(`Salaire en retard de ${outlook.salary.delayDays} jours`)
  expect(outlook.salary.delayDays).toBeGreaterThanOrEqual(28)

  // And the overdraft is forecast with its date, drawn as a zone and spelled out day by day.
  await expect(treasury.overdraft).toContainText('Découvert prévu le')
  await expect(treasury.overdraftZone).toBeVisible()
  await expect(treasury.days.first()).toHaveAttribute('data-overdraft', 'true')

  // And the advance advised is 420 € from the Livret.
  await expect(treasury.advice).toContainText('Livret MAG-280')
  await expect(treasury.advice).toContainText(euros(42000))

  // When I prepare the advance, the transaction form opens prefilled…
  await treasury.prepareAdvance.click()
  await expect(page).toHaveURL(/#\/transactions\/create\?source=/)
  await expect(page.getByLabel(/Libellé/)).toHaveValue('Avance vers le compte courant')

  // …and nothing was made: no advance exists until I save the form.
  const stored = await api.get('/api/finance/savings-advances')
  expect(((await stored.json()) as SavingsAdvances).advances).toEqual([])

  // When the bank allows a 500 € overdraft, nothing is announced any more.
  await treasury.open()
  await treasury.setLimit('500')
  await expect(treasury.overdraft).toHaveCount(0)
  await expect(treasury.advice).toHaveCount(0)
  await expect(treasury.content).not.toContainText('Aucun découvert')

  // When I choose a year, the curves run that far and the cumulated flows are drawn under the balance.
  await treasury.chooseHorizon('1 an', 12)
  await expect(treasury.flowsChart).toBeVisible()
  await expect(treasury.days.last()).toContainText(addMonths(today, 12, -1))

  // And hovering a day spells out its date and cumulated flows.
  await treasury.flowsChart.getByTestId('treasury-flows-day').first().hover()
  await expect(treasury.flowsDetail).toContainText('entrées cumulées')
  await expect(treasury.flowsDetail).toContainText('sorties cumulées')
})

test("the savings advances screen lists the advance and « Remettre » settles it", async ({ treasuryUser }) => {
  const { api, page } = treasuryUser
  const today = parisDay()

  // Given an advance of 1 500 € taken on the Livret during the salary delay.
  await givenAdvanceTaken(api, today)

  // When I open the savings advances screen.
  await page.goto(adminUrl(ROUTES.financeSavingsAdvances))

  // Then 1 500,00 € are left to give back, on the Livret's row, which offers « Remettre ».
  await expect(page.getByTestId('savings-total-outstanding')).toContainText('1 500,00')
  const row = page.getByTestId('savings-advance').filter({ hasText: 'Livret MAG-276' })
  await expect(row).toContainText('1 500,00')

  // When I give it back, the screen records the two legs of the transaction.
  await row.getByRole('button', { name: 'Remettre' }).click()

  // Then the advance is settled: 0,00 € left, no « Remettre » any more.
  await expect(page.getByTestId('savings-total-outstanding')).toHaveText(/^0,00/)
  await expect(row.getByRole('button', { name: 'Remettre' })).toHaveCount(0)
  await expect(row).toContainText('Soldée')
  const settled = await savingsAdvances(api)
  expect(settled.advances).toEqual([
    expect.objectContaining({ sourceAccountName: 'Livret MAG-276', repaidCents: 150000, outstandingCents: 0, isClosed: true }),
  ])
  expect(settled.proposals).toEqual([])
})

/**
 * Trésorerie, one page (MAG-632).
 *
 *   Étant donné un matelas, une avance d'épargne et une créance sur une personne
 *   Quand j'ouvre Finance › Trésorerie
 *   Alors il n'y a pas d'onglets
 *     et les sections courbe, matelas, avances, créances/dettes, mouvements sont là, dans cet ordre
 *   Quand je clique « Remettre » sur l'avance
 *   Alors elle est soldée, sur la même page
 */
test('the treasury is one page, with no tabs: curve, cushion, advances, debts, movements', async ({ treasuryUser }) => {
  const { api, page } = treasuryUser
  const today = parisDay()
  const suffix = Math.random().toString(36).slice(2, 8)
  const person = `Léa ${suffix}`

  // Given a cushion account of 3 000 €…
  const cushionAccount = await api.post('/api/accounts', {
    headers: LD,
    data: { name: 'Livret matelas MAG-632', type: 'savings', currency: 'EUR', balanceCents: 300000, isCushion: true },
  })
  expect(cushionAccount.status(), await cushionAccount.text()).toBe(201)
  createdAccountIds.push(String(((await cushionAccount.json()) as { id: string }).id))

  // …an advance of 1 500 € taken on the Livret…
  const { checkingId } = await givenAdvanceTaken(api, today)
  await line(api, checkingId, -30000, today, 'LOYER PREVU MAG-632', 'planned')

  // …and 200 € that a person owes on a shared expense.
  const contact = await api.post('/api/contacts', { headers: LD, data: { name: person } })
  expect(contact.status(), await contact.text()).toBe(201)
  const contactId = String(((await contact.json()) as { id: string }).id)
  const spend = await api.post('/api/transactions', {
    headers: LD,
    data: {
      account: `/api/accounts/${checkingId}`,
      label: `GITE MAG-632 ${suffix}`,
      amountCents: -40000,
      currency: 'EUR',
      bookedAt: shiftDay(today, -7),
      status: 'spent',
    },
  })
  expect(spend.status(), await spend.text()).toBe(201)
  const spendId = String(((await spend.json()) as { id: string }).id)
  const shared = await api.put(`/api/finance/transactions/${spendId}/my-share`, {
    data: { myShareCents: 20000, object: `Gîte ${suffix}`, shares: [{ contactId }] },
  })
  expect(shared.status(), await shared.text()).toBe(200)

  // When I open Finance › Trésorerie.
  const treasury = new FinanceTreasuryPage(page)
  await treasury.open()

  // Then there are no tabs…
  await expect(treasury.tabs).toHaveCount(0)

  // …and the five sections are there.
  await expect(treasury.chartSection).toBeVisible()
  await expect(treasury.cushionSection).toContainText('Livret matelas MAG-632')
  await expect(treasury.advancesSection).toContainText('Livret MAG-276')
  await expect(treasury.debtsSection).toContainText(person)
  await expect(treasury.movementsSection).toBeVisible()

  // MAG-697: the curve explains nothing under itself, and its labels keep the mockup's size.
  await expect(treasury.chartSection).not.toContainText('Survolez')
  const axisLabel = treasury.chart.locator('text').first()
  expect((await axisLabel.boundingBox())!.height).toBeLessThan(16)

  // And they follow one another: curve, cushion, advances, créances et dettes, movements.
  const order = await treasury.content
    .locator('[data-testid^="treasury-section-"]')
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-testid')))
  expect(order).toEqual([
    'treasury-section-chart',
    'treasury-section-cushion',
    'treasury-section-advances',
    'treasury-section-debts',
    'treasury-section-movements',
  ])

  // When I give the advance back from this page, it is settled without leaving it.
  await treasury.advance('Livret MAG-276').getByRole('button', { name: 'Remettre' }).click()
  await expect(treasury.advance('Livret MAG-276')).toContainText('Soldée')
  await expect(treasury.advancesSection.getByTestId('savings-total-outstanding')).toHaveText(/^0,00/)

  // And the settings are behind « Régler », in a dialog.
  await treasury.openSettings()
  await expect(treasury.settingsDialog.getByLabel('Jour du mois')).toBeVisible()
  await expect(treasury.settingsDialog.getByLabel('Cible (mois de revenu)')).toBeVisible()
})
