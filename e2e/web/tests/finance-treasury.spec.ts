import type { APIRequestContext } from '@playwright/test'
import { test, expect, parisDay } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { waitForIndexed } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * Les avances d'épargne (MAG-276, extends MAG-102).
 *
 *   Étant donné un salaire attendu le jour J et un repère de 4 950,00 €
 *     et aucun crédit de salaire depuis le jour J du mois dernier (30 jours dans un mois de 30)
 *     et un virement Livret → Courant de 1 500,00 € pendant le retard
 *   Quand je demande à Maggie « combien je dois remettre sur mon livret ? »
 *   Alors `get_savings_advances` a tourné et a réussi
 *     et l'API rend une avance en cours de 1 500,00 € sur le Livret
 *     et un retard de salaire compté depuis le jour J du mois dernier
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

interface SavingsAdvances {
  advances: Array<{ sourceAccountName: string; amountCents: number; outstandingCents: number; isClosed: boolean }>
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

async function account(api: APIRequestContext, name: string, type: string): Promise<string> {
  const response = await api.post('/api/accounts', {
    headers: LD,
    data: { name, type, currency: 'EUR', balanceCents: 0 },
  })
  expect(response.status(), await response.text()).toBe(201)
  const id = String(((await response.json()) as { id: string }).id)
  createdAccountIds.push(id)

  return id
}

async function line(api: APIRequestContext, accountId: string, amountCents: number, bookedAt: string, label: string): Promise<void> {
  const response = await api.post('/api/transactions', {
    headers: LD,
    data: { account: `/api/accounts/${accountId}`, label, amountCents, currency: 'EUR', bookedAt, status: 'spent' },
  })
  expect(response.status(), await response.text()).toBe(201)
}

test.afterEach(async ({ treasuryUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await treasuryUser.api.delete(`/api/accounts/${id}`)
  }
  await treasuryUser.api.patch('/api/finance/treasury-settings', { data: { salaryExpectedDay: null } })
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
  const checkingId = await account(api, 'Courant MAG-276', 'checking')
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

  // Then she read the savings advances, and it went through.
  expect(calledTools(events)).toContain('get_savings_advances')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'get_savings_advances', status: 'success' })]),
  )

  // And the API gives an advance of 1 500 € still owed to the Livret.
  const response = await api.get('/api/finance/savings-advances')
  expect(response.status(), await response.text()).toBe(200)
  const data = (await response.json()) as SavingsAdvances
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
})
