import type { APIRequestContext } from '@playwright/test'
import { test, expect, parisDay } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { FinanceRecurringPage } from '../pages/FinanceRecurringPage.js'

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
 * The annual plan reads the series (MAG-307):
 *
 *   Étant donné une opération récurrente « LOYER SCI VERDA » de 900,00 € le 5, avec ses trois débits rattachés
 *   Quand j'ouvre le plan annuel de l'année en cours
 *   Alors la catégorie du loyer compte 900,00 € par occurrence à venir, une seule fois chacune,
 *     et le débit déjà rattaché n'est pas compté deux fois
 *
 * Suggesting writes nothing: the series appears only on the explicit accept.
 * The first journey goes through the API; the second (MAG-308) goes through
 * the « Suggestions » panel of the recurring operations screen:
 *
 *   Étant donné trois débits « PRELEVEMENT ASSURANCE HABITA » de 31,00 € le 9, sur trois mois consécutifs
 *   Quand j'ouvre les opérations récurrentes
 *   Alors « Suggestions » propose la série, avec sa périodicité en clair et trois exemples
 *   Quand je clique « Créer la série » et je choisis la catégorie
 *   Alors la série apparaît dans la liste et la suggestion disparaît
 *   Quand je lance « Rattacher l'historique »
 *   Alors l'aperçu montre les trois débits avant d'écrire, et après confirmation ils sont rattachés
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

interface PlanCategory {
  categoryId: string
  recurringCents: number
  decidedCents: number
  suggestedCents: number
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
function lastBookedMonth(today: string, day = '05'): string {
  return today.slice(8, 10) >= day ? today.slice(0, 7) : monthBefore(today.slice(0, 7), 1)
}

/** How many transactions of the owner are attached to the series. */
async function attachedCount(api: APIRequestContext, seriesIri: string): Promise<number> {
  const response = await api.get('/api/transactions?itemsPerPage=100', { headers: { Accept: 'application/ld+json' } })
  const members = ((await response.json()) as { member?: StoredTransaction[] }).member ?? []

  return members.filter((t) => t.recurringOperation === seriesIri).length
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
 * Written on an account of its own (`recurringUser`), not the neighbour's: the
 * rent debits move the train de vie, which `finance-internal-transfers.spec.ts`
 * reads on the neighbour while this file runs on another worker. Deleting the
 * account takes the series and the lines with it.
 */
const createdAccountIds: string[] = []
const createdCategoryIds: string[] = []

test.afterEach(async ({ recurringUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await recurringUser.api.delete(`/api/accounts/${id}`)
  }
  for (const id of createdCategoryIds.splice(0)) {
    await recurringUser.api.delete(`/api/categories/${id}`)
  }
})

test('three rent debits are proposed as a monthly series, and accepting it creates and fills the series', async ({
  recurringUser,
}) => {
  const { api } = recurringUser
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

test('the suggestion is accepted from the screen, then the history is attached after its preview', async ({
  recurringUser,
}) => {
  const { api, page } = recurringUser
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-308, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  createdAccountIds.push(accountId)
  const categoryName = `Assurances MAG-308, essai ${attempt}`
  const categoryId = await created(api, '/api/categories', { name: categoryName, obligation: 'mandatory' })
  createdCategoryIds.push(categoryId)

  // Given three debits on the 9th, over three consecutive months already booked.
  const today = parisDay()
  const lastMonth = lastBookedMonth(today, '09')
  const days = [2, 1, 0].map((back) => `${monthBefore(lastMonth, back)}-09`)
  for (const day of days) {
    await created(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      label: 'PRELEVEMENT ASSURANCE HABITA',
      amountCents: -3100,
      currency: 'EUR',
      bookedAt: day,
      status: 'spent',
    })
  }
  const proposed = (await suggestions(api)).find((s) => s.accountId === accountId)
  expect(proposed, 'the insurance must be proposed').toBeDefined()
  const label = proposed?.label ?? ''

  // When I open the recurring operations.
  const recurring = new FinanceRecurringPage(page)
  await recurring.open()

  // Then the panel proposes it: why, how often, and three examples.
  await expect(recurring.suggestions).toContainText(label)
  await expect(recurring.suggestions).toContainText('3 transactions le 9 de chaque mois')
  await expect(recurring.suggestions).toContainText('PRELEVEMENT ASSURANCE HABITA')
  await expect(recurring.row(label)).toHaveCount(0)

  // When I create the series, choosing the heading the history does not name.
  await recurring.createSeries(label).click()
  await expect(recurring.form.getByLabel(/Libellé/)).toHaveValue(label)
  await recurring.choose(/Catégorie/, categoryName)
  await recurring.form.getByRole('button', { name: 'Créer la série' }).click()

  // Then the series is listed, and the suggestion is gone.
  await expect(recurring.row(label)).toContainText('Le 9 de chaque mois')
  await expect(recurring.row(label)).toContainText('31,00')
  await expect(recurring.suggestions).toHaveCount(0)

  // When I attach the history, the three debits are shown before anything is written.
  await recurring.catchUp.click()
  await expect(recurring.preview).toContainText('PRELEVEMENT ASSURANCE HABITA')
  await expect(recurring.preview.getByRole('row').filter({ hasText: label })).toHaveCount(3)
  const stored = await waitForIndexed<{ id: string; label?: string }>(
    api,
    '/api/recurring_operations',
    (candidate) => candidate.label === label,
    { what: 'The created series' },
  )
  const seriesIri = `/api/recurring_operations/${stored.id}`
  expect(await attachedCount(api, seriesIri), 'the preview writes nothing').toBe(0)

  // Then, confirmed, the three debits are attached.
  await recurring.preview.getByRole('button', { name: /^Rattacher ces \d+ transaction/ }).click()
  await expect(recurring.preview).toHaveCount(0)
  await expect.poll(() => attachedCount(api, seriesIri)).toBe(3)
})

test('the annual plan counts each upcoming rent occurrence once, and never the debits already attached', async ({
  recurringUser,
}) => {
  const { api } = recurringUser
  const attempt = test.info().retry

  const accountId = await created(api, '/api/accounts', {
    name: `Courant MAG-307, essai ${attempt}`,
    type: 'checking',
    currency: 'EUR',
    balanceCents: 0,
  })
  createdAccountIds.push(accountId)
  const categoryId = await created(api, '/api/categories', {
    name: `Logement MAG-307, essai ${attempt}`,
    obligation: 'mandatory',
  })
  createdCategoryIds.push(categoryId)

  // Given the rent series on the 5th, with its three booked debits attached.
  const today = parisDay()
  const year = Number(today.slice(0, 4))
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
  const rent = (await suggestions(api)).find((s) => s.accountId === accountId)
  expect(rent, 'the rent must be proposed').toBeDefined()
  const accepted = await api.post('/api/finance/recurring-operations/suggestions/accept', {
    headers: { 'Content-Type': 'application/json' },
    data: { suggestions: [{ ...rent, categoryId }] },
  })
  expect(accepted.status(), await accepted.text()).toBe(200)
  expect(((await accepted.json()) as AcceptResult).attached).toBe(3)

  // When I open the annual plan of the current year.
  // Then the rent counts 900,00 € for each 5th of the year the series has not
  // settled yet: the series starts at its anchor, the attached debits are out.
  const upcoming = Array.from({ length: 12 }, (_, index) => `${year}-${String(index + 1).padStart(2, '0')}-05`).filter(
    (day) => day >= days[0] && !days.includes(day),
  )
  expect(upcoming.length, 'the scenario must leave occurrences to come').toBeGreaterThan(0)

  await expect
    .poll(async () => {
      const response = await api.get(`/api/finance/annual-plan?year=${year}`, { headers: { Accept: 'application/json' } })
      expect(response.status(), await response.text()).toBe(200)
      const plan = (await response.json()) as { categories: PlanCategory[] }

      return plan.categories.find((category) => category.categoryId === categoryId)?.recurringCents
    })
    .toBe(upcoming.length * 90000)

  const response = await api.get(`/api/finance/annual-plan?year=${year}`, { headers: { Accept: 'application/json' } })
  const category = ((await response.json()) as { categories: PlanCategory[] }).categories.find(
    (candidate) => candidate.categoryId === categoryId,
  )
  expect(category?.decidedCents).toBe(upcoming.length * 90000)
  expect(category?.suggestedCents).toBe(upcoming.length * 90000)
})
