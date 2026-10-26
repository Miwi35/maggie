import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { emitMercure } from '../../test/mercure'
import { NutritionHomeCard } from './NutritionHomeCard'
import type { HomeDay } from '../home/homeDay'

const NOW = '2026-10-14T14:12:00+02:00'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const Where = () => {
  const location = useLocation()

  return <output data-testid="where">{`${location.pathname}${location.search}`}</output>
}

const renderCard = (day: HomeDay = 'today') =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={veilleuseLightTheme}>
        <NutritionHomeCard day={day} />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

const meal = (id: string, slot: 'lunch' | 'dinner', recipes: string[], extra: Record<string, unknown> = {}) => ({
  id,
  slot,
  date: '2026-10-14',
  summary: recipes.join(', '),
  guests: 2,
  recipes: recipes.map((_, i) => `/api/recipes/${id}-r${i}`),
  recipeNames: recipes,
  ...extra,
})

const need = (mealId: string, productId: string, name: string) => ({ productId, name, date: '2026-10-14', slot: 'dinner', mealId, mealName: 'x', missing: true })

const MEALS = [
  meal('m1', 'dinner', ['Lasagnes', 'Salade verte', 'Tiramisu'], { guests: 4 }),
  meal('m2', 'dinner', ['Soupe'], { guests: 1 }),
]
const NEEDS = [need('m1', 'p1', 'Parmesan'), need('m1', 'p2', 'Basilic'), need('m1', 'p1', 'Parmesan')]

const ok = (body: unknown) => ({ ok: true, status: 200, json: () => Promise.resolve(body) })
const failing = (status: number) => ({ ok: false, status, json: () => Promise.resolve({}) })

interface Answers {
  meals?: unknown[]
  needs?: unknown[]
  /** A response overriding the read of the meals or of the needs. */
  mealsResponse?: unknown
  needsResponse?: unknown
  /** The Cuisine activity in progress (`activity-status`); none by default. */
  status?: unknown
}

/** The reads of the card: the day's meals, what their recipes lack, the activity in progress. */
const reads = (answers: Answers = {}) =>
  vi.fn((url: string) => {
    if (url.includes('/activity-status/cuisine')) return Promise.resolve(answers.status ? ok(answers.status) : failing(404))
    if (url.includes('/grocery/recipe-needs')) return Promise.resolve(answers.needsResponse ?? ok({ member: answers.needs ?? NEEDS }))
    if (/\/meals\?/.test(url)) return Promise.resolve(answers.mealsResponse ?? ok({ member: answers.meals ?? MEALS }))

    return Promise.reject(new Error(`unexpected ${url}`))
  })

const callsTo = (mock: ReturnType<typeof vi.fn>, part: string) => mock.mock.calls.filter(([url]) => String(url).includes(part))

describe('NutritionHomeCard', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'test-jwt')
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(NOW))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('shows lunch then dinner, a meal with all its recipes on one line and its guests', async () => {
    vi.stubGlobal('fetch', reads())
    renderCard()

    const dinner = await screen.findByTestId('home-nutrition-dinner')

    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Déjeuner', 'Dîner'])
    const [first, second] = within(dinner).getAllByTestId('home-nutrition-meal')
    expect(within(first).getByTestId('home-nutrition-recipes')).toHaveTextContent('Lasagnes · Salade verte · Tiramisu')
    expect(within(first).getByTestId('home-nutrition-guests')).toHaveTextContent('4 pers.')
    expect(within(second).getByTestId('home-nutrition-recipes')).toHaveTextContent('Soupe')
    expect(within(second).getByTestId('home-nutrition-guests')).toHaveTextContent('1 pers.')
  })

  test('reads business resources only: two requests for the day and the activity in progress', async () => {
    const fetchMock = reads()
    vi.stubGlobal('fetch', fetchMock)
    renderCard()
    await screen.findByTestId('home-nutrition-dinner')

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(callsTo(fetchMock, '/activity-status/cuisine')).toHaveLength(1)
    expect(callsTo(fetchMock, '/home')).toHaveLength(0)
    const meals = new URL(String(callsTo(fetchMock, '/meals?')[0][0]))
    expect(meals.searchParams.get('date[after]')).toBe('2026-10-14')
    expect(meals.searchParams.get('date[before]')).toBe('2026-10-14')
    expect(meals.searchParams.get('pagination')).toBe('false')
    const needs = new URL(String(callsTo(fetchMock, '/grocery/recipe-needs')[0][0]))
    expect(needs.searchParams.get('from')).toBe('2026-10-14')
    expect(needs.searchParams.get('to')).toBe('2026-10-14')
    expect(needs.searchParams.get('missing')).toBe('true')
  })

  test('puts « — » on an empty slot', async () => {
    vi.stubGlobal('fetch', reads())
    renderCard()

    const lunch = await screen.findByTestId('home-nutrition-lunch')

    expect(within(lunch).getByTestId('home-nutrition-empty')).toHaveTextContent('—')
    expect(within(lunch).queryByTestId('home-nutrition-meal')).toBeNull()
    expect(within(screen.getByTestId('home-nutrition-dinner')).queryByTestId('home-nutrition-empty')).toBeNull()
  })

  test('is not drawn at all when no meal is planned for the day', async () => {
    vi.stubGlobal('fetch', reads({ meals: [], needs: [] }))
    renderCard()
    expect(screen.getByTestId('home-nutrition-loading')).toBeInTheDocument()

    await waitFor(() => expect(screen.queryByTestId('home-card-nutrition')).toBeNull())
    expect(screen.queryByText('Cuisine')).toBeNull()
    expect(screen.queryByTestId('home-nutrition-empty')).toBeNull()
  })

  test('stays drawn, with its error, when the read fails', async () => {
    vi.stubGlobal('fetch', reads({ mealsResponse: failing(500) }))
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    expect(screen.getByTestId('home-card-nutrition')).toBeInTheDocument()
  })

  test('stays drawn, with its error, when only the needs fail', async () => {
    vi.stubGlobal('fetch', reads({ needsResponse: failing(500) }))
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
  })

  test('says what is missing, once per product, only for the meal that lacks something', async () => {
    vi.stubGlobal('fetch', reads())
    renderCard()

    const [first, second] = await screen.findAllByTestId('home-nutrition-meal')

    expect(within(first).getByTestId('home-nutrition-missing')).toHaveTextContent('manque : Parmesan, Basilic')
    expect(within(second).queryByTestId('home-nutrition-missing')).toBeNull()
    expect(screen.getAllByTestId('home-nutrition-missing')).toHaveLength(1)
  })

  test('falls back to the title of a meal without recipe, and has nothing to launch for it', async () => {
    vi.stubGlobal('fetch', reads({ meals: [meal('m9', 'lunch', [], { summary: 'Restaurant' })], needs: [] }))
    renderCard()

    expect(await screen.findByTestId('home-nutrition-recipes')).toHaveTextContent('Restaurant')
    expect(screen.queryByRole('link', { name: /^Lancer/ })).toBeNull()
  })

  test('names the recipes, not the title the owner gave the meal', async () => {
    vi.stubGlobal('fetch', reads({ meals: [meal('m8', 'dinner', ['Pâtes', 'Salade'], { summary: 'Repas de fête' })], needs: [] }))
    renderCard()

    expect(await screen.findByTestId('home-nutrition-recipes')).toHaveTextContent('Pâtes · Salade')
  })

  test('asks for tomorrow, the user’s day plus one, when the selector is on Demain', async () => {
    const fetchMock = reads()
    vi.stubGlobal('fetch', fetchMock)
    renderCard('tomorrow')

    await screen.findByTestId('home-nutrition-dinner')

    expect(new URL(String(callsTo(fetchMock, '/meals?')[0][0])).searchParams.get('date[after]')).toBe('2026-10-15')
    expect(new URL(String(callsTo(fetchMock, '/grocery/recipe-needs')[0][0])).searchParams.get('from')).toBe('2026-10-15')
  })

  test('« ▶ Lancer » on a meal opens the Cuisine activity for that meal, from the Accueil', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', reads())
    renderCard()
    const [first, second] = await screen.findAllByTestId('home-nutrition-meal')

    expect(within(first).getByRole('link', { name: 'Lancer la cuisine de Lasagnes · Salade verte · Tiramisu' })).toHaveAttribute('href', '/activites/cuisine?meal=m1')
    await user.click(within(second).getByRole('link', { name: /^Lancer/ }))

    expect(screen.getByTestId('where')).toHaveTextContent('/activites/cuisine?meal=m2')
  })

  test('« ▶ Reprendre » replaces « Lancer » on the meal whose activity is in progress, and only on it', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', reads({ status: { origin: { kind: 'meal', id: 'm2' }, state: {}, updatedAt: '2026-10-14T12:00:00+00:00' } }))
    renderCard()
    const [first, second] = await screen.findAllByTestId('home-nutrition-meal')

    const resume = await within(second).findByRole('link', { name: /^Reprendre/ })
    expect(resume).toHaveTextContent('Reprendre')
    expect(within(first).getByRole('link', { name: /^Lancer/ })).toHaveTextContent('Lancer')
    await user.click(resume)

    expect(screen.getByTestId('where')).toHaveTextContent('/activites/cuisine?meal=m2')
  })

  test('a recipe\'s activity in progress does not turn a meal\'s pill into « Reprendre »', async () => {
    vi.stubGlobal('fetch', reads({ status: { origin: { kind: 'recipe', id: 'm2' }, state: {}, updatedAt: '2026-10-14T12:00:00+00:00' } }))
    renderCard()
    const [, second] = await screen.findAllByTestId('home-nutrition-meal')

    expect(within(second).getByRole('link', { name: /^Lancer/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^Reprendre/ })).toBeNull()
  })

  test('there is nothing to launch for tomorrow', async () => {
    vi.stubGlobal('fetch', reads())
    renderCard('tomorrow')
    await screen.findByTestId('home-nutrition-dinner')
    expect(screen.queryByTestId('home-launch-nutrition')).toBeNull()
  })

  test('opens the meals from its title', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', reads())
    renderCard()
    await screen.findByTestId('home-nutrition-dinner')

    await user.click(screen.getByRole('link', { name: 'Cuisine' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/meals')
  })

  test.each([['/api/meals/{id}'], ['/api/recipes/{id}'], ['/api/products/{id}'], ['/api/ingredients/{id}']])(
    'shows a loading state, then reads again when %s changes elsewhere',
    async (topic) => {
      const fetchMock = reads()
      vi.stubGlobal('fetch', fetchMock)
      renderCard()
      expect(screen.getByTestId('home-nutrition-loading')).toBeInTheDocument()
      await screen.findByTestId('home-nutrition-dinner')
      fetchMock.mockImplementation(reads({ meals: [meal('m3', 'lunch', ['Quiche']), ...MEALS], needs: NEEDS }).getMockImplementation()!)

      await act(async () => {
        await emitMercure(topic, { id: 'x' })
      })

      expect(await screen.findByText('Quiche')).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByTestId('home-nutrition-loading')).toBeNull())
    },
  )

  test('says it could not load, and tries again', async () => {
    const user = userEvent.setup()
    const fetchMock = reads({ mealsResponse: failing(500) })
    vi.stubGlobal('fetch', fetchMock)
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    fetchMock.mockImplementation(reads().getMockImplementation()!)

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('home-nutrition-dinner')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
