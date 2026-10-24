import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { emitMercure } from '../../test/mercure'
import { NutritionHomeCard } from './NutritionHomeCard'
import type { HomeDay } from '../home/homeDay'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const Where = () => <output data-testid="where">{useLocation().pathname}</output>

const renderCard = (day: HomeDay = 'today') =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={veilleuseLightTheme}>
        <NutritionHomeCard day={day} />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

const named = (id: string, name: string) => ({ id, name })

const meal = (id: string, recipes: string[], extra: Record<string, unknown> = {}) => ({
  id,
  title: recipes[0],
  guests: 2,
  recipes: recipes.map((name, i) => named(`${id}-r${i}`, name)),
  missing: [],
  ...extra,
})

const TODAY = {
  module: 'nutrition',
  day: 'today',
  date: '2026-10-14',
  slots: [
    { slot: 'lunch', meals: [] },
    {
      slot: 'dinner',
      meals: [
        meal('m1', ['Lasagnes', 'Salade verte', 'Tiramisu'], {
          guests: 4,
          missing: [named('i1', 'Parmesan'), named('i2', 'Basilic')],
        }),
        meal('m2', ['Soupe'], { guests: 1 }),
      ],
    },
  ],
}

const ok = (body: unknown) => ({ ok: true, json: () => Promise.resolve(body) })

describe('NutritionHomeCard', () => {
  beforeEach(() => localStorage.setItem('token', 'test-jwt'))
  afterEach(() => vi.unstubAllGlobals())

  test('shows lunch then dinner, a meal with all its recipes on one line and its guests', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(TODAY)))
    renderCard()

    const dinner = await screen.findByTestId('home-nutrition-dinner')

    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Déjeuner', 'Dîner'])
    const [first, second] = within(dinner).getAllByTestId('home-nutrition-meal')
    expect(within(first).getByTestId('home-nutrition-recipes')).toHaveTextContent('Lasagnes · Salade verte · Tiramisu')
    expect(within(first).getByTestId('home-nutrition-guests')).toHaveTextContent('4 pers.')
    expect(within(second).getByTestId('home-nutrition-recipes')).toHaveTextContent('Soupe')
    expect(within(second).getByTestId('home-nutrition-guests')).toHaveTextContent('1 pers.')
  })

  test('puts « — » on an empty slot', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(TODAY)))
    renderCard()

    const lunch = await screen.findByTestId('home-nutrition-lunch')

    expect(within(lunch).getByTestId('home-nutrition-empty')).toHaveTextContent('—')
    expect(within(lunch).queryByTestId('home-nutrition-meal')).toBeNull()
    expect(within(screen.getByTestId('home-nutrition-dinner')).queryByTestId('home-nutrition-empty')).toBeNull()
  })

  test('says what is missing, only for the meal that lacks something', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(TODAY)))
    renderCard()

    const [first, second] = await screen.findAllByTestId('home-nutrition-meal')

    expect(within(first).getByTestId('home-nutrition-missing')).toHaveTextContent('manque : Parmesan, Basilic')
    expect(within(second).queryByTestId('home-nutrition-missing')).toBeNull()
    expect(screen.getAllByTestId('home-nutrition-missing')).toHaveLength(1)
  })

  test('falls back to the title of a meal without recipe', async () => {
    const body = { slots: [{ slot: 'lunch', meals: [{ ...meal('m9', []), title: 'Restaurant' }] }, { slot: 'dinner', meals: [] }] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(body)))
    renderCard()

    expect(await screen.findByTestId('home-nutrition-recipes')).toHaveTextContent('Restaurant')
  })

  test('asks for the day it is given', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok(TODAY))
    vi.stubGlobal('fetch', fetchMock)
    renderCard('tomorrow')

    await screen.findByTestId('home-nutrition-dinner')

    expect(fetchMock).toHaveBeenCalledWith('/api/home/nutrition?day=tomorrow', expect.anything())
  })

  test('opens the meals from its title', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(TODAY)))
    renderCard()
    await screen.findByTestId('home-nutrition-dinner')

    await user.click(screen.getByRole('link', { name: 'Nutrition' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/meals')
  })

  test('shows a loading state, then reads again when the meals change elsewhere', async () => {
    const afterChange = { ...TODAY, slots: [{ slot: 'lunch', meals: [meal('m3', ['Quiche'])] }, TODAY.slots[1]] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok(TODAY)).mockResolvedValueOnce(ok(afterChange)))
    renderCard()
    expect(screen.getByTestId('home-nutrition-loading')).toBeInTheDocument()
    await screen.findByTestId('home-nutrition-dinner')

    await act(async () => {
      await emitMercure('/home/{module}', { action: 'refresh', module: 'nutrition' })
    })

    expect(await screen.findByText('Quiche')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('home-nutrition-loading')).toBeNull())
  })

  test('says it could not load, and tries again', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce({ ok: false, status: 500, json: () => Promise.resolve({}) }).mockResolvedValueOnce(ok(TODAY)),
    )
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('home-nutrition-dinner')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
