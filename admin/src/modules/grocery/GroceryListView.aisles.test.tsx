import { beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DndContextProps } from '@dnd-kit/core'
import { stubAislesApi } from '../../test/aislesApi'
import { GroceryListView } from './GroceryListView'

/**
 * A store's lines aisle by aisle (MAG-493): the headings, the order, a drag that
 * stays inside its aisle, and the aisle chosen from a line.
 */

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

const mockGetList = vi.fn()
const mockGetOne = vi.fn()
const mockNotify = vi.fn()
let dataProviderMock: { getList: typeof mockGetList; getOne: typeof mockGetOne } | undefined
vi.mock('react-admin', () => ({
  useDataProvider: () => (dataProviderMock ??= { getList: mockGetList, getOne: mockGetOne }),
  useNotify: () => mockNotify,
  Title: ({ title }: { title: string }) => <span>{title}</span>,
}))

// Each aisle is its own drop zone: the mock wraps each context in a marked div
// and keeps its props, so a test finishes a drag in the zone of the line it holds.
const zones = new Map<HTMLElement, DndContextProps>()
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: (props: DndContextProps) => (
      <div data-testid="dnd-zone" ref={(el) => void (el && zones.set(el, props))}>
        <actual.DndContext {...props} />
      </div>
    ),
  }
})

const CARREFOUR = { id: '01CARREFOUR', name: 'Carrefour', visitOrder: 1 }
const PRIMEUR = { id: '01PRIMEUR', name: 'Primeur', visitOrder: 2 }
const FRUITS = { id: 'a-fruits', name: 'Fruits et légumes', position: 1 }
const DAIRY = { id: 'a-dairy', name: 'Crèmerie', position: 2 }
const BAKERY = { id: 'a-bakery', name: 'Boulangerie', position: 3 }

const line = (id: string, label: string, position: number, extra: Record<string, unknown> = {}) => ({
  id,
  '@id': `/api/grocery_items/${id}`,
  label,
  checked: false,
  source: 'manual',
  store: CARREFOUR,
  position,
  product: { id: `p-${id}`, '@id': `/api/products/p-${id}`, name: label },
  ...extra,
})

const LIST = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [
    line('lait', 'Lait', 0, { aisle: DAIRY }),
    line('pommes', 'Pommes', 1, { aisle: FRUITS }),
    line('pain', 'Pain', 2, { aisle: BAKERY }),
    line('yaourt', 'Yaourt', 3, { aisle: DAIRY }),
    line('piles', 'Piles', 4, { aisle: null }),
    line('fraises', 'Fraises', 0, { store: PRIMEUR, aisle: null }),
    line('salade', 'Salade', 1, { store: PRIMEUR, aisle: null }),
  ],
}

const groupOf = (store: string) =>
  screen.getAllByTestId('grocery-store-group').find((g) => g.getAttribute('data-store') === store) as HTMLElement
const linesIn = (store: string) =>
  within(groupOf(store))
    .getAllByTestId('grocery-item')
    .map((el) => el.querySelector('[title]')?.getAttribute('title'))
const headingsIn = (store: string) =>
  within(groupOf(store))
    .queryAllByTestId('grocery-aisle-heading')
    .map((el) => el.textContent)

describe('GroceryListView — aisle by aisle (MAG-493)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    zones.clear()
    mockNotify.mockReset()
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    mockGetList.mockImplementation((resource: string) => {
      if (resource === 'grocery_lists') return Promise.resolve({ data: [LIST], total: 1 })
      if (resource === 'stores') return Promise.resolve({ data: [CARREFOUR, PRIMEUR], total: 2 })
      return Promise.resolve({ data: [], total: 0 })
    })
    mockGetOne.mockResolvedValue({ data: LIST })
  })

  test('a store with aisles lists its lines under their aisle, in the order of the way, « Sans rayon » last', async () => {
    stubAislesApi()
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Pommes')).toBeInTheDocument())

    expect(headingsIn('Carrefour')).toEqual(['Fruits et légumes', 'Crèmerie', 'Boulangerie', 'Sans rayon'])
    expect(linesIn('Carrefour')).toEqual(['Pommes', 'Lait', 'Yaourt', 'Pain', 'Piles'])
  })

  test('a store with no aisle keeps its plain list, with no heading', async () => {
    stubAislesApi()
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Fraises')).toBeInTheDocument())

    expect(headingsIn('Primeur')).toEqual([])
    expect(linesIn('Primeur')).toEqual(['Fraises', 'Salade'])
  })

  test('a drag moves a line inside its aisle and renumbers the whole store in the order drawn', async () => {
    const { fetchMock } = stubAislesApi()
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Yaourt')).toBeInTheDocument())

    const zone = screen.getByText('Yaourt').closest('[data-testid="dnd-zone"]') as HTMLElement
    expect(within(zone).queryByText('Pommes'), 'another aisle is another drop zone').not.toBeInTheDocument()
    await act(async () => {
      await zones.get(zone)?.onDragEnd?.({ active: { id: 'yaourt' }, over: { id: 'lait' } } as never)
    })

    expect(linesIn('Carrefour')).toEqual(['Pommes', 'Yaourt', 'Lait', 'Pain', 'Piles'])
    const reorder = fetchMock.mock.calls.find(([url]) => String(url).includes('/grocery/reorder'))
    expect(JSON.parse(String(reorder?.[1]?.body)).items).toEqual([
      { id: 'pommes', position: 0 },
      { id: 'yaourt', position: 1 },
      { id: 'lait', position: 2 },
      { id: 'pain', position: 3 },
      { id: 'piles', position: 4 },
    ])
  })

  test("choosing an aisle from a line puts its product there, its other stores' aisles kept", async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi(
      [
        { store: '01CARREFOUR', ...FRUITS },
        { store: '01CARREFOUR', ...DAIRY },
        { store: '01CARREFOUR', ...BAKERY },
      ],
      { products: { 'p-piles': [{ store: '01PRIMEUR', aisle: 'x-elsewhere' }] } },
    )
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Piles')).toBeInTheDocument())

    await user.click(screen.getByText('Piles'))
    const dialog = await screen.findByRole('dialog')
    const aisle = within(dialog).getByLabelText('Rayon')
    expect(aisle).toHaveValue('')
    await user.click(aisle)
    await user.click(await screen.findByRole('option', { name: 'Boulangerie' }))
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(writes.some((w) => w.url.endsWith('/products/p-piles'))).toBe(true))
    expect(writes.find((w) => w.url.endsWith('/products/p-piles'))?.body).toEqual({
      aisles: [
        { store: '01PRIMEUR', aisle: 'x-elsewhere' },
        { store: '01CARREFOUR', aisle: 'a-bakery' },
      ],
    })
    expect(writes.some((w) => w.method === 'POST')).toBe(false)
  })

  test('a new aisle name typed on a line creates the aisle in its store first', async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi([{ store: '01CARREFOUR', ...FRUITS }])
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Piles')).toBeInTheDocument())

    await user.click(screen.getByText('Piles'))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Rayon'), 'Bazar')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(writes.some((w) => w.url.endsWith('/products/p-piles'))).toBe(true))
    expect(writes.find((w) => w.method === 'POST')?.body).toEqual({ store: '/api/stores/01CARREFOUR', name: 'Bazar' })
    expect(writes.find((w) => w.url.endsWith('/products/p-piles'))?.body).toEqual({
      aisles: [{ store: '01CARREFOUR', aisle: 'new-1' }],
    })
  })

  test('emptying the aisle of a line leaves its product « Sans rayon » in that store', async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi([{ store: '01CARREFOUR', ...DAIRY }], {
      products: { 'p-lait': [{ store: '01CARREFOUR', aisle: 'a-dairy' }] },
    })
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Lait')).toBeInTheDocument())

    await user.click(screen.getByText('Lait'))
    const dialog = await screen.findByRole('dialog')
    const aisle = within(dialog).getByLabelText('Rayon')
    expect(aisle).toHaveValue('Crèmerie')
    await user.clear(aisle)
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(writes.some((w) => w.url.endsWith('/products/p-lait'))).toBe(true))
    expect(writes.find((w) => w.url.endsWith('/products/p-lait'))?.body).toEqual({ aisles: [] })
  })

  test('an aisle the API refuses is said, the line itself saved', async () => {
    const user = userEvent.setup()
    stubAislesApi([], { failWrites: true })
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Piles')).toBeInTheDocument())

    await user.click(screen.getByText('Piles'))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Rayon'), 'Bazar')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Ce magasin a déjà ce rayon.', { type: 'error' }))
  })

  test('a line with no product, or with no store, has no aisle to choose', async () => {
    const user = userEvent.setup()
    stubAislesApi()
    const list = {
      ...LIST,
      items: [line('libre', 'Ampoules', 0, { product: null }), line('nulle-part', 'Bougies', 1, { store: undefined })],
    }
    mockGetList.mockImplementation((resource: string) =>
      Promise.resolve(resource === 'grocery_lists' ? { data: [list], total: 1 } : { data: [], total: 0 }),
    )
    mockGetOne.mockResolvedValue({ data: list })
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Ampoules')).toBeInTheDocument())

    await user.click(screen.getByText('Ampoules'))
    expect(within(await screen.findByRole('dialog')).queryByLabelText('Rayon')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Annuler' }))

    await user.click(screen.getByText('Bougies'))
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
    expect(within(screen.getByRole('dialog')).queryByLabelText('Rayon')).not.toBeInTheDocument()
  })
})
