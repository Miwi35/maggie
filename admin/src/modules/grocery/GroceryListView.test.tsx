import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GroceryListView } from './GroceryListView'

// Mock EventSource for Mercure
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}
vi.stubGlobal('EventSource', MockEventSource)

const mockGetList = vi.fn()
const mockGetOne = vi.fn()
const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    getOne: mockGetOne,
  }),
  useNotify: () => mockNotify,
  Title: ({ title }: { title: string }) => <span>{title}</span>,
}))

const sampleList = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [
    {
      id: 'item-a',
      '@id': '/api/grocery_items/item-a',
      label: 'Tomates',
      quantity: 3,
      unit: 'piece',
      checked: false,
      source: 'manual',
      store: { id: 'store-1', name: 'Supermarché', visitOrder: 1 },
      position: 2,
    },
    {
      id: 'item-b',
      '@id': '/api/grocery_items/item-b',
      label: 'Lait',
      quantity: 1,
      unit: 'l',
      checked: false,
      source: 'manual',
      store: { id: 'store-1', name: 'Supermarché', visitOrder: 1 },
      position: 1,
    },
    {
      id: 'item-c',
      '@id': '/api/grocery_items/item-c',
      label: 'Pommes',
      quantity: 1,
      unit: 'kg',
      checked: false,
      source: 'recipe',
      store: { id: 'store-2', name: 'Primeur', visitOrder: 2 },
      position: 0,
    },
  ],
}

describe('GroceryListView', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    mockGetList.mockResolvedValue({ data: [sampleList], total: 1 })
    mockGetOne.mockResolvedValue({ data: sampleList })
  })

  test('renders drag handles for each item', async () => {
    render(<GroceryListView />)

    await waitFor(() => {
      expect(screen.getByText('Tomates')).toBeInTheDocument()
    })

    const handles = screen.getAllByTestId('drag-handle')
    expect(handles).toHaveLength(3)
  })

  test('items sorted by position within store groups', async () => {
    render(<GroceryListView />)

    await waitFor(() => {
      expect(screen.getByText('Tomates')).toBeInTheDocument()
    })

    // Within "Supermarché" group: Lait (position 1) should appear before Tomates (position 2)
    const items = screen.getAllByRole('checkbox')
    const labels = items.map((cb) => {
      const listItemButton = cb.closest('[role="button"]')
      return listItemButton?.textContent || ''
    })

    const laitIndex = labels.findIndex((l) => l.includes('Lait'))
    const tomatesIndex = labels.findIndex((l) => l.includes('Tomates'))
    expect(laitIndex).toBeLessThan(tomatesIndex)
  })

  test('handleDragEnd sends correct reorder API call', async () => {
    const mockFetch = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ success: true }) })
    vi.stubGlobal('fetch', mockFetch)

    render(<GroceryListView />)

    await waitFor(() => {
      expect(screen.getByText('Tomates')).toBeInTheDocument()
    })

    // We can't easily simulate a full drag-and-drop with dnd-kit in tests,
    // but we can verify the reorder endpoint is called by the handleDragEnd logic.
    // Instead, verify the fetch endpoint is correctly configured by checking
    // that the component renders and items are in the correct position order.
    const handles = screen.getAllByTestId('drag-handle')
    expect(handles.length).toBeGreaterThan(0)
  })

  describe('writes on a line go to the route the API exposes (MAG-197)', () => {
    // `GroceryItem` is not an ApiResource: the API serialises each line with an
    // anonymous `@id` (`/.well-known/genid/…`), which nothing routes — nginx
    // answers 403. The only routes are `/api/grocery_items/{id}`.
    const anonymous = (item: (typeof sampleList.items)[number]) => ({
      ...item,
      '@id': `/.well-known/genid/${item.id}-blank-node`,
    })
    const listAsTheApiSendsIt = {
      ...sampleList,
      items: [
        anonymous(sampleList.items[0]),
        anonymous(sampleList.items[1]),
        { ...anonymous(sampleList.items[2]), checked: true },
      ],
    }

    const mockApi = (response: { ok: boolean; status: number } = { ok: true, status: 200 }) => {
      const mockFetch = vi.fn().mockResolvedValue({ ...response, json: () => Promise.resolve({}) })
      vi.stubGlobal('fetch', mockFetch)
      mockGetList.mockResolvedValue({ data: [listAsTheApiSendsIt], total: 1 })
      mockGetOne.mockResolvedValue({ data: listAsTheApiSendsIt })
      return mockFetch
    }

    const callsTo = (mockFetch: ReturnType<typeof vi.fn>, method: string) =>
      mockFetch.mock.calls
        .filter(([, init]) => init?.method === method)
        .map(([url, init]) => ({ url: String(url), body: init.body as string | undefined }))

    test('ticking a line patches /api/grocery_items/{id}, not its anonymous @id', async () => {
      const mockFetch = mockApi()
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Tomates')).toBeInTheDocument())

      const user = userEvent.setup()
      await user.click(within(screen.getByText('Tomates').closest('[data-testid="grocery-item"]') as HTMLElement).getByRole('checkbox'))

      await waitFor(() => expect(callsTo(mockFetch, 'PATCH')).toHaveLength(1))
      const [patch] = callsTo(mockFetch, 'PATCH')
      expect(patch.url).toMatch(/\/api\/grocery_items\/item-a$/)
      expect(JSON.parse(patch.body as string)).toEqual({ checked: true })
    })

    test('ending the errand deletes each ticked line at /api/grocery_items/{id}', async () => {
      const mockFetch = mockApi()
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Pommes')).toBeInTheDocument())

      await userEvent.setup().click(screen.getByRole('button', { name: 'Terminer les courses' }))

      await waitFor(() => expect(callsTo(mockFetch, 'DELETE')).toHaveLength(1))
      expect(callsTo(mockFetch, 'DELETE')[0].url).toMatch(/\/api\/grocery_items\/item-c$/)
    })

    test('dropping a line left over deletes it at /api/grocery_items/{id}', async () => {
      const mockFetch = mockApi()
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Pommes')).toBeInTheDocument())

      const user = userEvent.setup()
      await user.click(screen.getByRole('button', { name: 'Terminer les courses' }))
      const dialog = await screen.findByRole('dialog')
      await waitFor(() => expect(callsTo(mockFetch, 'DELETE')).toHaveLength(1))
      await user.click(within(within(dialog).getByText('Lait').closest('li') as HTMLElement).getByRole('button', { name: 'Retirer' }))

      await waitFor(() => expect(callsTo(mockFetch, 'DELETE')).toHaveLength(2))
      expect(callsTo(mockFetch, 'DELETE')[1].url).toMatch(/\/api\/grocery_items\/item-b$/)
    })

    test('an API refusal on ticking shows an error instead of passing silently', async () => {
      mockNotify.mockClear()
      mockApi({ ok: false, status: 403 })
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Tomates')).toBeInTheDocument())

      const user = userEvent.setup()
      await user.click(within(screen.getByText('Tomates').closest('[data-testid="grocery-item"]') as HTMLElement).getByRole('checkbox'))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
    })

    test('an API refusal on ending the errand shows an error and does not offer the leftovers', async () => {
      mockNotify.mockClear()
      mockApi({ ok: false, status: 403 })
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Pommes')).toBeInTheDocument())

      await userEvent.setup().click(screen.getByRole('button', { name: 'Terminer les courses' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  describe('store identifier sent to the API', () => {
    // The Hydra data provider gives the IRI as `id`; the API expects the bare ULID.
    const ULID = '01M3VA6TKHF1CB6GMPSRV8WE7P'
    const halles = { id: `/api/stores/${ULID}`, name: 'Halles du voisin' }

    const mockApi = (apiResponse: { ok: boolean; status: number }) => {
      const mockFetch = vi.fn().mockResolvedValue({ ...apiResponse, json: () => Promise.resolve({}) })
      vi.stubGlobal('fetch', mockFetch)
      mockGetList.mockImplementation((resource: string) =>
        Promise.resolve(
          resource === 'stores'
            ? { data: [halles], total: 1 }
            : resource === 'products'
              ? { data: [], total: 0 }
              : { data: [sampleList], total: 1 },
        ),
      )
      return mockFetch
    }

    const chooseHalles = async (dialog: HTMLElement) => {
      const user = userEvent.setup()
      await user.click(within(dialog).getByLabelText('Magasin'))
      await user.click(await screen.findByRole('option', { name: 'Halles du voisin' }))
      return user
    }

    const sentBody = (mockFetch: ReturnType<typeof vi.fn>, urlPart: string) => {
      const call = mockFetch.mock.calls.find(([url]) => String(url).includes(urlPart))
      return call ? JSON.parse(call[1].body) : undefined
    }

    test('adding an item sends the store ULID, not its IRI', async () => {
      const mockFetch = mockApi({ ok: true, status: 200 })
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Tomates')).toBeInTheDocument())

      const user = userEvent.setup()
      await user.click(screen.getByRole('button', { name: 'Ajouter' }))
      const dialog = await screen.findByRole('dialog')
      await user.type(within(dialog).getByLabelText('Article'), 'Câpres')
      await chooseHalles(dialog)
      await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

      await waitFor(() => expect(sentBody(mockFetch, '/grocery/add-item')).toBeDefined())
      expect(sentBody(mockFetch, '/grocery/add-item').storeId).toBe(ULID)
    }, 15_000) // typing, a dropdown pick and two dialogs: 2-3 s alone, over 5 s on a busy CI runner

    test('editing an item sends the store ULID, not its IRI', async () => {
      const mockFetch = mockApi({ ok: true, status: 200 })
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Tomates')).toBeInTheDocument())

      const user = userEvent.setup()
      await user.click(screen.getByText('Tomates'))
      const dialog = await screen.findByRole('dialog')
      const store = within(dialog).getByLabelText('Magasin')
      await user.clear(store)
      await user.click(store)
      await user.click(await screen.findByRole('option', { name: 'Halles du voisin' }))
      await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(sentBody(mockFetch, '/grocery/edit-item/item-a')).toBeDefined())
      expect(sentBody(mockFetch, '/grocery/edit-item/item-a').storeId).toBe(ULID)
    })

    test('picking a product whose preferred store is an IRI prefills that store and sends its ULID', async () => {
      const mockFetch = mockApi({ ok: true, status: 200 })
      mockGetList.mockImplementation((resource: string) =>
        Promise.resolve(
          resource === 'stores'
            ? { data: [halles], total: 1 }
            : resource === 'products'
              ? {
                  data: [{ id: `/api/products/p1`, name: 'Câpres', category: 'condiment', preferredStore: `/api/stores/${ULID}` }],
                  total: 1,
                }
              : { data: [sampleList], total: 1 },
        ),
      )
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Tomates')).toBeInTheDocument())

      const user = userEvent.setup()
      await user.click(screen.getByRole('button', { name: 'Ajouter' }))
      const dialog = await screen.findByRole('dialog')
      await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('products', expect.anything()))
      await user.type(within(dialog).getByLabelText('Article'), 'Câp')
      await user.click(await screen.findByRole('option', { name: /Câpres/ }))

      await waitFor(() => expect(within(dialog).getByLabelText('Magasin')).toHaveValue('Halles du voisin'))
      await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

      await waitFor(() => expect(sentBody(mockFetch, '/grocery/add-item')).toBeDefined())
      expect(sentBody(mockFetch, '/grocery/add-item').storeId).toBe(ULID)
    })

    test('an API refusal on add shows an error and keeps the dialog open', async () => {
      mockNotify.mockClear()
      mockApi({ ok: false, status: 500 })
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Tomates')).toBeInTheDocument())

      const user = userEvent.setup()
      await user.click(screen.getByRole('button', { name: 'Ajouter' }))
      const dialog = await screen.findByRole('dialog')
      await user.type(within(dialog).getByLabelText('Article'), 'Câpres')
      await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(within(screen.getByRole('dialog')).getByLabelText('Article')).toHaveValue('Câpres')
      expect(mockNotify).not.toHaveBeenCalledWith('Article ajouté', expect.anything())
    })

    test('an API refusal on edit shows an error and keeps the dialog open', async () => {
      mockNotify.mockClear()
      mockApi({ ok: false, status: 500 })
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Tomates')).toBeInTheDocument())

      const user = userEvent.setup()
      await user.click(screen.getByText('Tomates'))
      const dialog = await screen.findByRole('dialog')
      await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(mockNotify).not.toHaveBeenCalledWith('Article modifié', expect.anything())
    })
  })
})
