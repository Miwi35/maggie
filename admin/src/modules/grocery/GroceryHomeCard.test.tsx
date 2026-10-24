import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { emitMercure } from '../../test/mercure'
import { GroceryHomeCard } from './GroceryHomeCard'
import { recipeNeedsLabel } from './homeRecipeNeeds'
import type { HomeDay } from '../home/homeDay'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const Where = () => <output data-testid="where">{useLocation().pathname}</output>

const renderCard = (day: HomeDay = 'today') =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={veilleuseLightTheme}>
        <GroceryHomeCard day={day} />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

const item = (id: string, name: string, extra: Record<string, unknown> = {}) => ({
  id,
  name,
  quantity: null,
  unit: null,
  needed: null,
  ...extra,
})

const SIX_AT_THE_SUPERMARKET = {
  count: 8,
  forRecipes: [],
  stores: [
    {
      id: 's1',
      name: 'Supermarché',
      count: 6,
      more: 1,
      items: [
        item('1', 'Pommes', { quantity: 6, unit: 'piece' }),
        item('2', 'Beurre'),
        item('3', 'Pain'),
        item('4', 'Sel', { needed: [{ quantity: 300, unit: 'g' }] }),
        item('5', 'Sucre'),
      ],
    },
    { id: 's2', name: 'Primeur', count: 1, more: 0, items: [item('6', 'Radis')] },
    { id: null, name: null, count: 1, more: 0, items: [item('7', 'Piles')] },
  ],
}

const ok = (body: unknown) => ({ ok: true, json: () => Promise.resolve(body) })

describe('GroceryHomeCard', () => {
  beforeEach(() => localStorage.setItem('token', 'test-jwt'))
  afterEach(() => vi.unstubAllGlobals())

  test('lists the open lines store by store, cut with « + n », with no button or checkbox', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(SIX_AT_THE_SUPERMARKET)))
    renderCard()

    const stores = await screen.findAllByTestId('home-grocery-store')

    expect(stores.map((s) => within(s).getByRole('heading').textContent)).toEqual(['Supermarché', 'Primeur', 'Non assigné'])
    const supermarket = within(stores[0])
    expect(supermarket.getAllByTestId('home-grocery-item').map((li) => li.querySelector('p')?.textContent)).toEqual([
      'Pommes',
      'Beurre',
      'Pain',
      'Sel',
      'Sucre',
    ])
    expect(supermarket.getByTestId('home-grocery-more')).toHaveTextContent('+ 1')
    expect(within(stores[1]).queryByTestId('home-grocery-more')).toBeNull()
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })

  test('tells a line’s quantity, preferring what the meals need', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(SIX_AT_THE_SUPERMARKET)))
    renderCard()

    const [supermarket] = await screen.findAllByTestId('home-grocery-store')

    expect(within(supermarket).getByText('6 pièces')).toBeInTheDocument()
    expect(within(supermarket).getByText('300 g')).toBeInTheDocument()
  })

  test('opens Courses from its title', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(SIX_AT_THE_SUPERMARKET)))
    renderCard()
    await screen.findAllByTestId('home-grocery-store')

    await user.click(screen.getByRole('link', { name: 'Courses' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/grocery')
  })

  test('puts what the next meals lack above the list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        ok({
          ...SIX_AT_THE_SUPERMARKET,
          forRecipes: [
            { name: 'Parmesan', day: 'today', slot: 'dinner' },
            { name: 'Crème fraîche', day: 'tomorrow', slot: 'lunch' },
          ],
        }),
      ),
    )
    renderCard()

    const recipes = await screen.findByTestId('home-grocery-recipes')

    expect(recipes).toHaveTextContent('Pour les recettes · parmesan (ce soir), crème fraîche (demain)')
    expect(recipes.compareDocumentPosition(screen.getAllByTestId('home-grocery-store')[0]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  test('says so when there is nothing to buy', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok({ count: 0, forRecipes: [], stores: [] })))
    renderCard()

    expect(await screen.findByTestId('home-grocery-empty')).toHaveTextContent('Rien à acheter')
  })

  test('reads the list again when an item is ticked elsewhere', async () => {
    const afterTick = {
      ...SIX_AT_THE_SUPERMARKET,
      count: 7,
      stores: [
        { ...SIX_AT_THE_SUPERMARKET.stores[0], count: 5, more: 0, items: SIX_AT_THE_SUPERMARKET.stores[0].items.filter((i) => i.name !== 'Pain') },
        ...SIX_AT_THE_SUPERMARKET.stores.slice(1),
      ],
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok(SIX_AT_THE_SUPERMARKET)).mockResolvedValueOnce(ok(afterTick)))
    renderCard()
    await screen.findByText('Pain')

    await act(async () => {
      await emitMercure('/home/{module}', { action: 'refresh', module: 'grocery' })
    })

    await waitFor(() => expect(screen.queryByText('Pain')).toBeNull())
    expect(screen.queryByTestId('home-grocery-more')).toBeNull()
  })

  test('says it could not load, and tries again', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce({ ok: false, status: 500, json: () => Promise.resolve({}) }).mockResolvedValueOnce(ok(SIX_AT_THE_SUPERMARKET)),
    )
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findAllByTestId('home-grocery-store')).toHaveLength(3)
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
