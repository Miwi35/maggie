import type { APIRequestContext } from '@playwright/test'
import { test, expect, parisDay } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { waitForIndexed } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

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

test.describe.configure({ retries: 0 })

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

test.afterEach(async ({ treasuryUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await treasuryUser.api.delete(`/api/accounts/${id}`)
  }
  await treasuryUser.api.patch('/api/finance/treasury-settings', { data: { salaryExpectedDay: null, overdraftLimitCents: 0 } })
})

test('« combien je dois remettre sur mon livret ? » reads the advance taken during the delay', async ({ treasuryUser }) => {
  const { api, page } = treasuryUser
  const today = parisDay()

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

  // When I ask Maggie how much to put back on my Livret.
  await new DashboardPage(page).open()
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
 * La vue trésorerie (MAG-279) — the screen comes with MAG-280:
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
