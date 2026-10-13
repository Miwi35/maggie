import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { GroceryListView } from './GroceryListView'

// MAG-387 — a line on a phone: two rows, nothing laid over the name.

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

const dataProvider = { getList: vi.fn(), getOne: vi.fn() }
vi.mock('react-admin', () => ({
  useDataProvider: () => dataProvider,
  useNotify: () => vi.fn(),
  Title: ({ title }: { title: string }) => <span>{title}</span>,
}))

const LONG = 'Pâtes sèches, standard, crues'

const line = (id: string, label: string, source: string, position: number) => ({
  id,
  '@id': `/.well-known/genid/${id}`,
  label,
  product: { packagingUnit: 'pack', packagingSize: null, packagingSizeUnit: null },
  quantity: 4,
  unit: 'pack',
  checked: false,
  source,
  store: { id: 'store-1', name: 'Supermarché', visitOrder: 1 },
  position,
})

const list = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [line('item-a', LONG, 'recipe', 0), line('item-b', 'charbon', 'manual', 1)],
}

/** A window `width` px wide, answering `(max-width:Npx)` like a browser would. */
function windowOf(width: number) {
  vi.stubGlobal('matchMedia', (query: string) => {
    const max = /max-width:([\d.]+)px/.exec(query)
    return {
      matches: max ? width <= Number(max[1]) : false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
    }
  })
}

const row = async (label: string) => {
  const text = await screen.findByText(label)
  return text.closest('[data-testid="grocery-item"]') as HTMLElement
}

describe('GroceryListView line, 390px wide', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    dataProvider.getList.mockResolvedValue({ data: [list], total: 1 })
    dataProvider.getOne.mockResolvedValue({ data: list })
    windowOf(390)
  })

  afterEach(() => vi.unstubAllGlobals())

  test('the name is one truncated line carrying its full text', async () => {
    render(<GroceryListView />)
    const name = within(await row(LONG)).getByText(LONG)

    expect(name).toHaveAttribute('title', LONG)
    expect(name).toHaveStyle({ whiteSpace: 'nowrap', textOverflow: 'ellipsis', overflow: 'hidden' })
  })

  test('the badge and the quantity sit on a second row, in the flow', async () => {
    render(<GroceryListView />)
    const item = await row(LONG)
    const controls = within(item).getByTestId('grocery-item-controls')
    const badge = within(controls).getByText('Recette').closest('.MuiChip-root') as HTMLElement

    expect(item).toHaveStyle({ flexWrap: 'wrap' })
    expect(controls).toHaveStyle({ flexBasis: '100%', justifyContent: 'flex-end' })
    expect(controls).toContainElement(within(item).getByTestId('quantity-stepper'))
    expect(controls).toContainElement(within(item).getByRole('button', { name: `Supprimer ${LONG}` }))

    // Not inside the name's button, and positioned by the flow rather than over it.
    expect(within(item).getAllByRole('button').find((b) => b.contains(badge) && b.classList.contains('MuiListItemButton-root'))).toBeUndefined()
    expect(getComputedStyle(badge).position).not.toBe('absolute')
    expect(getComputedStyle(badge).flexShrink).toBe('0')
  })

  test('on a desk the line stays on one row and the name may wrap', async () => {
    windowOf(1440)
    render(<GroceryListView />)
    const item = await row(LONG)
    const name = within(item).getByText(LONG)

    expect(item).toHaveStyle({ flexWrap: 'nowrap' })
    expect(name).not.toHaveStyle({ whiteSpace: 'nowrap' })
    expect(within(item).getByTestId('grocery-item-controls')).not.toHaveStyle({ flexBasis: '100%' })
  })
})
