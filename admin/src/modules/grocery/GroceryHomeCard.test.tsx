import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { emitMercure } from '../../test/mercure'
import { GroceryHomeCard } from './GroceryHomeCard'
import { recipeNeedsLabel } from './homeRecipeNeeds'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const NOW = '2026-10-14T14:12:00+02:00'

const Where = () => <output data-testid="where">{useLocation().pathname}</output>

const renderCard = () =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={veilleuseLightTheme}>
        <GroceryHomeCard />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

const item = (id: string, label: string, extra: Record<string, unknown> = {}) => ({
  id,
  label,
  quantity: null,
  unit: null,
  needed: null,
  checked: false,
  position: Number(id),
  ...extra,
})

const SUPERMARKET = { id: 's1', name: 'Supermarché', visitOrder: 2 }
const GREENGROCER = { id: 's2', name: 'Primeur', visitOrder: 1 }

const SIX_AT_THE_SUPERMARKET = [
  item('1', 'Pommes', { quantity: 6, unit: 'piece', store: SUPERMARKET }),
  item('2', 'Beurre', { store: SUPERMARKET }),
  item('3', 'Pain', { store: SUPERMARKET }),
  item('4', 'Sel', { needed: [{ quantity: 300, unit: 'g' }], store: SUPERMARKET }),
  item('5', 'Sucre', { store: SUPERMARKET }),
  item('6', 'Farine', { store: SUPERMARKET }),
  item('7', 'Radis', { store: GREENGROCER }),
  item('8', 'Piles'),
]

const ok = (body: unknown) => ({ ok: true, status: 200, json: () => Promise.resolve(body) })
const failing = (status: number) => ({ ok: false, status, json: () => Promise.resolve({}) })

const NEEDS = [
  { productId: 'p1', name: 'Parmesan', date: '2026-10-14', slot: 'dinner', mealId: 'm1', mealName: 'Pâtes', missing: true },
  { productId: 'p2', name: 'Crème fraîche', date: '2026-10-15', slot: 'lunch', mealId: 'm2', mealName: 'Gratin', missing: true },
]

interface Answers {
  items?: unknown[]
  needs?: unknown[]
  /** Status (or a response) overriding the read of the lists, the list, or the needs. */
  lists?: unknown
  list?: unknown
  recipeNeeds?: unknown
}

/** The three reads of the card: the lists, the open list, the recipe needs. */
const reads = (answers: Answers = {}) =>
  vi.fn((url: string) => {
    if (url.includes('/grocery/recipe-needs')) return Promise.resolve(answers.recipeNeeds ?? ok({ member: answers.needs ?? [], totalItems: 0 }))
    if (/\/grocery_lists\/l1/.test(url)) return Promise.resolve(answers.list ?? ok({ id: 'l1', items: answers.items ?? [] }))
    if (url.includes('/grocery_lists')) return Promise.resolve(answers.lists ?? ok({ member: [{ id: 'l1' }] }))

    return Promise.reject(new Error(`unexpected ${url}`))
  })

const callsTo = (mock: ReturnType<typeof vi.fn>, part: string) => mock.mock.calls.filter(([url]) => String(url).includes(part))

describe('GroceryHomeCard', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'test-jwt')
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(NOW))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('lists the open lines store by store, cut with « + n », with no button or checkbox', async () => {
    vi.stubGlobal('fetch', reads({ items: SIX_AT_THE_SUPERMARKET }))
    renderCard()

    const stores = await screen.findAllByTestId('home-grocery-store')

    expect(stores.map((s) => within(s).getByRole('heading').textContent)).toEqual(['Primeur', 'Supermarché', 'Non assigné'])
    const supermarket = within(stores[1])
    expect(supermarket.getAllByTestId('home-grocery-item').map((li) => li.querySelector('p')?.textContent)).toEqual([
      'Pommes',
      'Beurre',
      'Pain',
      'Sel',
      'Sucre',
    ])
    expect(supermarket.getByTestId('home-grocery-more')).toHaveTextContent('+ 1')
    expect(within(stores[0]).queryByTestId('home-grocery-more')).toBeNull()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })

  test('reads business resources only: three requests', async () => {
    const fetchMock = reads({ items: SIX_AT_THE_SUPERMARKET, needs: NEEDS })
    vi.stubGlobal('fetch', fetchMock)
    renderCard()
    await screen.findAllByTestId('home-grocery-store')

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(callsTo(fetchMock, '/home')).toHaveLength(0)
    const needs = new URL(String(callsTo(fetchMock, '/grocery/recipe-needs')[0][0]), 'http://api')
    expect(Object.fromEntries(needs.searchParams)).toEqual({ from: '2026-10-14', to: '2026-10-15', missing: 'true' })
  })

  test('tells a line’s quantity, preferring what the meals need', async () => {
    vi.stubGlobal('fetch', reads({ items: SIX_AT_THE_SUPERMARKET }))
    renderCard()

    const [, supermarket] = await screen.findAllByTestId('home-grocery-store')

    expect(within(supermarket).getByText('6 pièces')).toBeInTheDocument()
    expect(within(supermarket).getByText('300 g')).toBeInTheDocument()
  })

  test('opens Courses from its title', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', reads({ items: SIX_AT_THE_SUPERMARKET }))
    renderCard()
    await screen.findAllByTestId('home-grocery-store')

    await user.click(screen.getByRole('link', { name: 'Courses' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/grocery')
  })

  test('puts what the next meals lack above the list', async () => {
    vi.stubGlobal('fetch', reads({ items: SIX_AT_THE_SUPERMARKET, needs: NEEDS }))
    renderCard()

    const recipes = await screen.findByTestId('home-grocery-recipes')

    expect(recipes).toHaveTextContent('Pour les recettes · parmesan (ce soir), crème fraîche (demain)')
    expect(recipes.compareDocumentPosition(screen.getAllByTestId('home-grocery-store')[0]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  test('is not drawn at all when there is nothing to buy and nothing missing for a recipe', async () => {
    vi.stubGlobal('fetch', reads())
    renderCard()
    expect(screen.getByTestId('home-grocery-loading')).toBeInTheDocument()

    await waitFor(() => expect(screen.queryByTestId('home-card-grocery')).toBeNull())
    expect(screen.queryByText('Courses')).toBeNull()
    expect(screen.queryByText(/Rien à acheter/)).toBeNull()
  })

  test('is not drawn when the user has no list yet', async () => {
    vi.stubGlobal('fetch', reads({ lists: ok({ member: [] }) }))
    renderCard()

    await waitFor(() => expect(screen.queryByTestId('home-grocery-loading')).toBeNull())
    expect(screen.queryByTestId('home-card-grocery')).toBeNull()
  })

  test('stays drawn when only a recipe lacks something', async () => {
    vi.stubGlobal('fetch', reads({ needs: [NEEDS[0]] }))
    renderCard()

    expect(await screen.findByTestId('home-grocery-recipes')).toBeInTheDocument()
  })

  test('treats a module that is absent as empty, not as an error', async () => {
    vi.stubGlobal('fetch', reads({ items: SIX_AT_THE_SUPERMARKET, recipeNeeds: failing(404) }))
    renderCard()

    expect(await screen.findAllByTestId('home-grocery-store')).toHaveLength(3)
    expect(screen.queryByTestId('home-grocery-recipes')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  test('is not drawn when the grocery module is absent', async () => {
    vi.stubGlobal('fetch', reads({ lists: failing(404) }))
    renderCard()

    await waitFor(() => expect(screen.queryByTestId('home-grocery-loading')).toBeNull())
    expect(screen.queryByTestId('home-card-grocery')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  test('comes back when the list fills up', async () => {
    const answers: Answers = {}
    vi.stubGlobal('fetch', reads(answers))
    renderCard()
    await waitFor(() => expect(screen.queryByTestId('home-card-grocery')).toBeNull())

    answers.items = SIX_AT_THE_SUPERMARKET
    await act(async () => {
      await emitMercure('/api/grocery_lists/{id}', { '@id': '/api/grocery_lists/l1' })
    })

    expect(await screen.findAllByTestId('home-grocery-store')).toHaveLength(3)
  })

  test.each(['/api/grocery_lists/{id}', '/api/stores/{id}', '/api/products/{id}', '/api/ingredients/{id}', '/api/aisles/{id}', '/api/meals/{id}', '/api/recipes/{id}'])(
    'reads again when %s publishes',
    async (topic) => {
      const answers: Answers = { items: SIX_AT_THE_SUPERMARKET }
      const fetchMock = reads(answers)
      vi.stubGlobal('fetch', fetchMock)
      renderCard()
      await screen.findByText('Pain')

      answers.items = SIX_AT_THE_SUPERMARKET.filter((i) => i.label !== 'Pain')
      await act(async () => {
        await emitMercure(topic, { '@id': topic })
      })

      await waitFor(() => expect(screen.queryByText('Pain')).toBeNull())
      expect(screen.queryByTestId('home-grocery-more')).toBeNull()
    },
  )

  test('says it could not load, and tries again', async () => {
    const user = userEvent.setup()
    const answers: Answers = { items: SIX_AT_THE_SUPERMARKET, list: failing(500) }
    vi.stubGlobal('fetch', reads(answers))
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    expect(screen.getByTestId('home-card-grocery')).toBeInTheDocument()

    answers.list = undefined
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findAllByTestId('home-grocery-store')).toHaveLength(3)
  })

  test('keeps what it showed when a refresh fails', async () => {
    const answers: Answers = { items: SIX_AT_THE_SUPERMARKET }
    vi.stubGlobal('fetch', reads(answers))
    renderCard()
    await screen.findByText('Pain')

    answers.recipeNeeds = failing(500)
    await act(async () => {
      await emitMercure('/api/meals/{id}', {})
    })

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('Pain')).toBeInTheDocument()
  })
})

describe('recipeNeedsLabel', () => {
  test('names the moment of each meal', () => {
    expect(
      recipeNeedsLabel([
        { name: 'Parmesan', day: 'today', slot: 'dinner' },
        { name: 'Citron', day: 'today', slot: 'lunch' },
        { name: 'Crème', day: 'tomorrow', slot: 'dinner' },
      ]),
    ).toBe('parmesan (ce soir), citron (ce midi), crème (demain)')
  })
})
