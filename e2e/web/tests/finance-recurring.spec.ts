import { test, expect, seedId, parisDay } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'

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
 */

interface StoredSeries {
  id?: string
  label?: string
  counterpartyKey?: string | null
  referenceAmountCents?: number
  monthlyCostCents?: number
  yearlyCostCents?: number
}

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
