import type { APIRequestContext } from '@playwright/test'
import { test, expect, parisDay } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'

/**
 * Detection of recurring operations (MAG-47), extending the recurring
 * operations journey (MAG-102):
 *
 *   Étant donné trois débits « PRELEVEMENT LOYER SCI VERDA » de 900,00 € le 5, sur trois mois consécutifs
 *     et aucune opération récurrente
 *   Quand j'ouvre les suggestions
 *   Alors « LOYER SCI VERDA » est proposé, mensuel, ancré le 5, 900,00 €
 *   Quand j'accepte la suggestion
 *   Alors l'opération récurrente existe, ses trois débits y sont rattachés
 *     et sa prochaine échéance est le 5 du mois suivant
 *
 * Suggesting writes nothing: the series appears only on the explicit accept.
 * The screens are out of scope; the journey goes through the API they will call.
 */

interface Suggestion {
  label: string
  period: string
  anchorOn: string
  dayRule: string
  referenceAmountCents: number
  occurrences: number
  accountId: string
  categoryId: string | null
  labelPattern: string | null
}

interface AcceptResult {
  success: boolean
  created: number
  labels: string[]
  attached: number
}

interface OverviewOperation {
  id: string
  label: string
  nextOccurrenceOn: string | null
}

interface StoredTransaction {
  id?: string
  recurringOperation?: string | null
}

/** The month `back` months before `month` (YYYY-MM). */
function monthBefore(month: string, back: number): string {
  const [year, m] = month.split('-').map(Number)
  const index = year * 12 + (m - 1) - back

  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}

/** The month of the latest 5th already passed. */
function lastBookedMonth(today: string): string {
  return today.slice(8, 10) >= '05' ? today.slice(0, 7) : monthBefore(today.slice(0, 7), 1)
}

async function created(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<string> {
  const response = await api.post(path, { headers: { 'Content-Type': 'application/ld+json' }, data })
  expect(response.status(), await response.text()).toBe(201)

  return String(((await response.json()) as { id?: string }).id ?? '')
}

async function suggestions(api: APIRequestContext): Promise<Suggestion[]> {
  const response = await api.get('/api/finance/recurring-operations/suggestions')
  expect(response.status(), await response.text()).toBe(200)

  return ((await response.json()) as { suggestions: Suggestion[] }).suggestions
}

/**
 * Written as the neighbour, on an account and a category of its own: deleting
 * the account takes the series and the lines with it.
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

test('three rent debits are proposed as a monthly series, and accepting it creates and fills the series', async ({
  otherUser,
}) => {
  const { api } = otherUser
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-47, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  createdAccountIds.push(accountId)
  const categoryId = await created(api, '/api/categories', {
    name: `Logement MAG-47, essai ${attempt}`,
    obligation: 'mandatory',
  })
  createdCategoryIds.push(categoryId)

  // Given three debits on the 5th, over three consecutive months already booked.
  const today = parisDay()
  const lastMonth = lastBookedMonth(today)
  const days = [2, 1, 0].map((back) => `${monthBefore(lastMonth, back)}-05`)
  for (const day of days) {
    await created(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      label: 'PRELEVEMENT LOYER SCI VERDA',
      amountCents: -90000,
      currency: 'EUR',
      bookedAt: day,
      status: 'spent',
    })
  }

  // When I open the suggestions.
  const rent = (await suggestions(api)).find((s) => s.accountId === accountId)

  // Then the rent is proposed: monthly, anchored on the 5th, 900,00 €.
  expect(rent, 'the rent must be proposed').toBeDefined()
  expect(rent?.label).toBe('LOYER SCI VERDA')
  expect(rent?.period).toBe('monthly')
  expect(rent?.anchorOn).toBe(days[0])
  expect(rent?.dayRule).toBe('fixed_day')
  expect(rent?.referenceAmountCents).toBe(-90000)
  expect(rent?.occurrences).toBe(3)

  // And nothing was created by looking.
  const before = await api.get('/api/finance/recurring-operations/overview')
  expect(((await before.json()) as { operations: OverviewOperation[] }).operations.find((o) => o.label === 'LOYER SCI VERDA')).toBeUndefined()

  // When I accept it, with the heading I choose.
  const accepted = await api.post('/api/finance/recurring-operations/suggestions/accept', {
    headers: { 'Content-Type': 'application/json' },
    data: { suggestions: [{ ...rent, categoryId }] },
  })
  expect(accepted.status(), await accepted.text()).toBe(200)
  const result = (await accepted.json()) as AcceptResult
  expect(result.created).toBe(1)
  expect(result.labels).toEqual(['LOYER SCI VERDA'])
  expect(result.attached).toBe(3)

  // Then the series exists.
  const series = await waitForIndexed<{ id: string; label: string; referenceAmountCents: number }>(
    api,
    '/api/recurring_operations',
    (s) => s.label === 'LOYER SCI VERDA',
    { what: 'The accepted recurring operation' },
  )
  expect(series.referenceAmountCents).toBe(-90000)

  // And its three debits are attached.
  const seriesIri = `/api/recurring_operations/${series.id}`
  for (const day of days) {
    const line = await waitForIndexed<StoredTransaction & { bookedAt?: string }>(
      api,
      '/api/transactions',
      (t) => t.recurringOperation === seriesIri && (t as { bookedAt?: string }).bookedAt?.slice(0, 10) === day,
      { what: `The debit of ${day} attached to the series` },
    )
    expect(line.recurringOperation).toBe(seriesIri)
  }

  // And the next due date is the 5th of the month after the last debit.
  const overview = await api.get('/api/finance/recurring-operations/overview')
  const operation = ((await overview.json()) as { operations: OverviewOperation[] }).operations.find((o) => o.id === series.id)
  expect(operation?.nextOccurrenceOn).toBe(`${monthBefore(lastMonth, -1)}-05`)

  // And the payee is no longer proposed.
  expect((await suggestions(api)).find((s) => s.accountId === accountId)).toBeUndefined()
})
