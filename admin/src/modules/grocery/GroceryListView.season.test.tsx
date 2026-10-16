import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { GroceryListView } from './GroceryListView'

// MAG-494 — a line says whether its product is in season this month.

// useMercure keeps one stream per topic set for the whole file: the source may come from an earlier test.
const eventSources: MockEventSource[] = []

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    eventSources.push(this)
  }
}

const dataProvider = { getList: vi.fn(), getOne: vi.fn() }
vi.mock('react-admin', () => ({
  useDataProvider: () => dataProvider,
  useNotify: () => vi.fn(),
  Title: ({ title }: { title: string }) => <span>{title}</span>,
}))

const line = (id: string, label: string, inSeason: boolean | null | undefined, position: number) => ({
  id,
  '@id': `/.well-known/genid/${id}`,
  label,
  product: { id: `product-${id}`, name: label },
  checked: false,
  source: 'manual',
  position,
  ...(inSeason === undefined ? {} : { inSeason }),
})

const list = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [
    line('item-a', 'Potiron', true, 0),
    line('item-b', 'Tomates', false, 1),
    line('item-c', 'Lessive', null, 2),
    line('item-d', 'Bougies', undefined, 3),
  ],
}

const row = async (label: string) => {
  const text = await screen.findByText(label)
  return text.closest('[data-testid="grocery-item"]') as HTMLElement
}

describe('GroceryListView season badge', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    dataProvider.getList.mockResolvedValue({ data: [list], total: 1 })
    dataProvider.getOne.mockResolvedValue({ data: list })
  })

  afterEach(() => vi.unstubAllGlobals())

  test('a product in season carries « de saison »', async () => {
    render(<GroceryListView />)

    expect(within(await row('Potiron')).getByTestId('season-badge')).toHaveTextContent('de saison')
  })

  test('a product out of season carries « hors saison »', async () => {
    render(<GroceryListView />)

    expect(within(await row('Tomates')).getByTestId('season-badge')).toHaveTextContent('hors saison')
  })

  test('a product without a season, or a free-text line, has no badge', async () => {
    render(<GroceryListView />)

    expect(within(await row('Lessive')).queryByTestId('season-badge')).toBeNull()
    expect(within(await row('Bougies')).queryByTestId('season-badge')).toBeNull()
  })

  test('a Mercure update of the list brings the new badge', async () => {
    render(<GroceryListView />)
    await row('Tomates')

    // The API now serves the line in season, for any reload the update triggers.
    const updated = { ...list, items: list.items.map((item) => (item.id === 'item-b' ? { ...item, inSeason: true } : item)) }
    dataProvider.getOne.mockResolvedValue({ data: updated })
    const source = eventSources.findLast((s) => decodeURIComponent(s.url).includes('/api/grocery_lists/'))
    act(() => {
      source!.onmessage?.({
        data: JSON.stringify({ '@id': '/api/grocery_lists/list-1', items: updated.items }),
      } as MessageEvent)
    })

    await waitFor(async () =>
      expect(within(await row('Tomates')).getByTestId('season-badge')).toHaveTextContent('de saison'),
    )
  })
})
