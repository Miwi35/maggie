import { afterEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { emitMercure } from '../../test/mercure'
import { veilleuseLightTheme } from '../../theme'
import { NutritionPage } from './NutritionPage'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

const balance = (eatenKcal: number) => ({
  date: '2026-10-21',
  eatenKcal,
  plannedKcal: 0,
  expendedKcal: 1900,
  balanceKcal: eatenKcal - 1900,
  proteinG: 0,
  carbsG: 0,
  fatG: 0,
  plannedMeals: [],
})

const SALAD = {
  id: '01SALAD',
  date: '2026-10-21',
  eatenAt: '2026-10-21T12:00:00+02:00',
  label: 'Salade César',
  kcal: 450,
  proteinG: 25,
  carbsG: 20,
  fatG: 30,
  source: 'conversation',
}

// What Maggie records (manage_food_intakes) or a meal changed elsewhere reads the day again: the journal is served by Doctrine.
describe('NutritionPage — live updates from Mercure', () => {
  afterEach(() => vi.unstubAllGlobals())

  test.each([
    ['an intake Maggie recorded', '/api/health/food-intakes/{id}', { '@id': '/api/health/food-intakes/01SALAD', ...SALAD }],
    ['a meal changed', '/api/meals/{id}', { '@id': '/api/meals/01MEAL' }],
  ])('%s reads the day again', async (_name, topic, payload) => {
    let told = false
    const fetcher = vi.fn(async (url: string) => {
      const { pathname, searchParams } = new URL(url, 'http://localhost')
      if (pathname === '/api/health/daily-balance') {
        return searchParams.has('date') ? json(balance(told ? 450 : 0)) : json({ days: [] })
      }
      if (pathname === '/api/health/food-intakes') return json({ member: told ? [SALAD] : [] })

      return json({})
    })
    vi.stubGlobal('fetch', fetcher)
    render(
      <MemoryRouter>
        <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme}>
          <NutritionPage />
        </AdminContext>
      </MemoryRouter>,
    )
    await waitFor(() => expect(screen.getByTestId('balance-parts')).toHaveTextContent(/^Mangé 0 kcal/))

    told = true
    await emitMercure(topic, payload)

    await waitFor(() => expect(screen.getByTestId('balance-parts')).toHaveTextContent(/^Mangé 450 kcal/))
    const journal = screen.getByRole('region', { name: 'Journal' })
    expect(await within(journal).findByTestId('intake-row-01SALAD')).toHaveTextContent('Maggie')
  })
})
