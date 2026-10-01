import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GroceryListView } from './GroceryListView'

/**
 * The handles `e2e/web/pages/GroceryListPage.ts` reaches for.
 *
 * A contract between this screen and the grocery journeys (MAG-101), in the same
 * spirit as `api/contract/`: the journeys address the heading, the two buttons,
 * every line, every aisle and both dialogs by role, accessible name or test id,
 * and nothing in a normal component test notices when one of those moves. The
 * journeys would — several minutes later, on a stack that takes minutes to
 * start, with a failure reading "the grocery list is broken" rather than "a
 * label was renamed".
 *
 * `data-testid="grocery-item"` and its `data-store` exist for them, and the
 * reason is worth keeping written down: MUI renders a store's `ListSubheader`
 * as an `<li>` too, so `getByRole('listitem')` matches the aisle headings as
 * well as the lines, and a filter on a shop's name would return a heading plus
 * everything under it.
 *
 * `GroceryListView.test.tsx` owns the behaviour — drag handles, sorting, the
 * reorder call. This file owns the names, and asserts no behaviour beyond what
 * it takes to open a dialog.
 */

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

const mockGetList = vi.fn()
const mockGetOne = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    getOne: mockGetOne,
  }),
  useNotify: () => vi.fn(),
  Title: ({ title }: { title: string }) => <span>{title}</span>,
}))

const STORES = {
  supermarket: { id: 'store-1', name: 'Supermarché Leclerc', visitOrder: 1 },
  greengrocer: { id: 'store-2', name: 'Primeur du marché', visitOrder: 2 },
}

/**
 * One line per case the journeys address: a shop with the lowest visit order, a
 * shop with the next one, a line with no shop at all, and a line already in the
 * trolley — the state "Terminer les courses" needs to exist at all.
 */
const LIST = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [
    {
      id: 'item-pasta',
      '@id': '/api/grocery_items/item-pasta',
      label: 'Pâtes complètes',
      quantity: 500,
      unit: 'g',
      checked: false,
      source: 'recipe',
      store: STORES.supermarket,
      position: 2,
    },
    {
      id: 'item-tomato',
      '@id': '/api/grocery_items/item-tomato',
      label: 'Tomate',
      quantity: 6,
      unit: 'piece',
      checked: false,
      source: 'recipe',
      store: STORES.greengrocer,
      position: 1,
    },
    {
      id: 'item-custom',
      '@id': '/api/grocery_items/item-custom',
      label: 'Pile LR03',
      quantity: 4,
      unit: 'piece',
      checked: false,
      source: 'manual',
      position: 4,
    },
    {
      id: 'item-milk',
      '@id': '/api/grocery_items/item-milk',
      label: 'Lait demi-écrémé',
      quantity: 1,
      unit: 'l',
      checked: true,
      source: 'recurring',
      store: STORES.supermarket,
      position: 3,
    },
  ],
}

const PRODUCTS = [
  { id: 'product-1', name: 'Tomate', category: 'produce', defaultUnit: 'piece', preferredStore: STORES.greengrocer },
]

/** What `GroceryListPage.line()` does, in the same way. */
function line(label: string): HTMLElement {
  const found = screen
    .getAllByTestId('grocery-item')
    .filter((item) => (item.textContent ?? '').includes(label))

  expect(found, `no single line labelled ${label}`).toHaveLength(1)

  return found[0]
}

describe('GroceryListView — the handles the grocery journeys use', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    // The view writes with bare `fetch`, not through the data provider: ending
    // an errand deletes each ticked line that way, and an unstubbed `fetch`
    // throws, so the dialog this file asserts on would never open.
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({}) } as Response)))
    localStorage.setItem('token', 'test-jwt')
    mockGetList.mockImplementation((resource: string) => {
      if ('grocery_lists' === resource) return Promise.resolve({ data: [LIST], total: 1 })
      if ('products' === resource) return Promise.resolve({ data: PRODUCTS, total: PRODUCTS.length })
      if ('stores' === resource) {
        return Promise.resolve({ data: Object.values(STORES), total: 2 })
      }

      return Promise.resolve({ data: [], total: 0 })
    })
    mockGetOne.mockResolvedValue({ data: LIST })
  })

  test('the screen the journeys open names itself, and offers both actions', async () => {
    render(<GroceryListView />)

    await waitFor(() => expect(screen.getByText('Ma liste de courses')).toBeInTheDocument())

    expect(screen.getByRole('button', { name: 'Ajouter' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Terminer les courses' })).toBeInTheDocument()
    // `checked/total` — the chip a journey reads to know where the trip is up to.
    expect(screen.getByText('1/4')).toBeInTheDocument()
  })

  test('every line is a grocery-item carrying its shop', async () => {
    render(<GroceryListView />)

    await waitFor(() => expect(screen.getByText('Pâtes complètes')).toBeInTheDocument())

    expect(screen.getAllByTestId('grocery-item')).toHaveLength(4)
    expect(line('Pâtes complètes')).toHaveAttribute('data-store', STORES.supermarket.name)
    expect(line('Tomate')).toHaveAttribute('data-store', STORES.greengrocer.name)
    // Empty, not absent: a line with no shop still has to be addressable, and
    // the group it is drawn under is the view's label for "nowhere".
    expect(line('Pile LR03')).toHaveAttribute('data-store', '')

    // The tick box the shopper presses, one per line, reflecting its state.
    expect(within(line('Tomate')).getByRole('checkbox')).not.toBeChecked()
    expect(within(line('Lait demi-écrémé')).getByRole('checkbox')).toBeChecked()
  })

  test('the aisles are groups in visit order', async () => {
    render(<GroceryListView />)

    await waitFor(() => expect(screen.getByText('Pâtes complètes')).toBeInTheDocument())

    const order = screen.getAllByTestId('grocery-store-group').map((group) => group.getAttribute('data-store'))

    // `visitOrder` 1, then 2, then the group for lines with no shop — which the
    // view sorts on PHP_INT_MAX rather than on its name.
    expect(order).toEqual([STORES.supermarket.name, STORES.greengrocer.name, 'Non assigné'])
  })

  test('the add dialog carries the four fields a journey fills', async () => {
    const user = userEvent.setup()
    render(<GroceryListView />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajouter' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajouter' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Ajouter un article')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Article')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Quantité')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Magasin')).toBeInTheDocument()
    // Same name as the button that opened it, which is why the journey scopes
    // its click to the dialog.
    expect(within(dialog).getByRole('button', { name: 'Ajouter' })).toBeInTheDocument()
  })

  test('ending the errand lists what is left, each with a way to drop it', async () => {
    const user = userEvent.setup()
    render(<GroceryListView />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Terminer les courses' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Terminer les courses' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Articles restants')).toBeInTheDocument()

    // The three unticked lines, and not the one already in the trolley.
    const remaining = within(dialog).getAllByRole('listitem')
    expect(remaining).toHaveLength(3)
    expect(within(dialog).queryByText('Lait demi-écrémé')).not.toBeInTheDocument()

    const tomato = remaining.find((item) => (item.textContent ?? '').includes('Tomate'))
    expect(tomato).toBeDefined()
    expect(within(tomato as HTMLElement).getByRole('button', { name: 'Retirer' })).toBeInTheDocument()
  })
})
