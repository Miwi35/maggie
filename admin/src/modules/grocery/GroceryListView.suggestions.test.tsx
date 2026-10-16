import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GroceryListView } from './GroceryListView'

// MAG-495 — « Souvent pris »: the products bought regularly and due again, on top of the list.

const eventSources: MockEventSource[] = []
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    eventSources.push(this)
  }
}

const mockGetList = vi.fn()
const mockGetOne = vi.fn()
const mockNotify = vi.fn()
const dataProvider = { getList: mockGetList, getOne: mockGetOne }
vi.mock('react-admin', () => ({
  useDataProvider: () => dataProvider,
  useNotify: () => mockNotify,
  Title: ({ title }: { title: string }) => <span>{title}</span>,
}))

const CAFE = '01M3VA6TKHF1CB6GMPSRV8WCAF'

const sampleList = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [
    {
      id: 'item-lait',
      '@id': '/.well-known/genid/item-lait',
      label: 'Lait',
      quantity: 1,
      unit: 'l',
      checked: false,
      source: 'manual',
      store: { id: 'store-1', name: 'Supermarché', visitOrder: 1 },
      position: 0,
    },
  ],
}

const cafe = {
  product: { id: CAFE, name: 'Café' },
  everyDays: 14,
  lastBoughtOn: '2026-10-01',
  dueOn: '2026-10-15',
  usualQuantity: 2,
  usualUnit: 'pack',
}

const respond = (status: number, body: unknown = {}) =>
  Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) } as Response)

/** `/api/grocery-suggestions` answers `suggestions` (read at each call); the writes answer `writeStatus`. */
function stubApi({ suggestions = [cafe] as unknown[], writeStatus = 200, failWrites = false } = {}) {
  const state = { suggestions }
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (method !== 'GET' && failWrites) return Promise.reject(new Error('offline'))
    if (method !== 'GET') return respond(writeStatus, { success: writeStatus < 400 })
    if (String(input).endsWith('/grocery-suggestions')) return respond(200, state.suggestions)
    return respond(200)
  })
  vi.stubGlobal('fetch', fetchMock)
  const calls = (method: string, part: string) =>
    fetchMock.mock.calls
      .filter(([url, init]) => (init?.method ?? 'GET') === method && String(url).includes(part))
      .map(([url, init]) => ({ url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined }))
  return { fetchMock, state, calls, suggestionReads: () => calls('GET', '/grocery-suggestions').length }
}

const banner = () => screen.getByTestId('grocery-suggestions')

const open = async () => {
  render(<GroceryListView />)
  await screen.findByText('Lait')
}

describe('GroceryListView — « Souvent pris »', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    eventSources.length = 0
    mockNotify.mockClear()
    mockGetList.mockClear()
    mockGetOne.mockClear()
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    mockGetList.mockResolvedValue({ data: [sampleList], total: 1 })
    mockGetOne.mockResolvedValue({ data: sampleList })
  })

  afterEach(() => {
    localStorage.removeItem('user')
    vi.unstubAllGlobals()
  })

  test('shows each product due with its rhythm', async () => {
    stubApi()
    await open()

    expect(await within(await screen.findByTestId('grocery-suggestions')).findByText('Café · toutes les 2 sem.')).toBeInTheDocument()
    expect(within(banner()).getByText('Souvent pris (1)')).toBeInTheDocument()
    expect(within(banner()).getByRole('button', { name: 'Ajouter Café' })).toBeInTheDocument()
    expect(within(banner()).getByRole('button', { name: 'Pas besoin de Café' })).toBeInTheDocument()
  })

  test('no banner when nothing is due', async () => {
    const api = stubApi({ suggestions: [] })
    await open()

    await waitFor(() => expect(api.suggestionReads()).toBe(1))
    expect(screen.queryByTestId('grocery-suggestions')).toBeNull()
    expect(screen.queryByText(/Souvent pris/)).toBeNull()
  })

  test('collapsing the banner hides its rows', async () => {
    stubApi()
    await open()
    await screen.findByText('Café · toutes les 2 sem.')

    const user = userEvent.setup()
    await user.click(within(banner()).getByRole('button', { name: /Souvent pris/ }))

    await waitFor(() => expect(screen.queryByText('Café · toutes les 2 sem.')).toBeNull())
    expect(screen.queryByRole('button', { name: 'Ajouter Café' })).toBeNull()

    await user.click(within(banner()).getByRole('button', { name: /Souvent pris/ }))
    expect(await screen.findByText('Café · toutes les 2 sem.')).toBeInTheDocument()
  })

  test('« Ajouter » adds the product to the list at its usual quantity', async () => {
    const api = stubApi()
    await open()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Ajouter Café' }))

    await waitFor(() => expect(api.calls('POST', '/grocery/add-item')).toHaveLength(1))
    expect(api.calls('POST', '/grocery/add-item')[0].body).toEqual({ label: 'Café', quantity: 2, unit: 'pack' })
    expect(mockNotify).toHaveBeenCalledWith('Article ajouté', { type: 'success' })
  })

  test('an added product leaves the banner', async () => {
    const api = stubApi()
    await open()
    const user = userEvent.setup()
    api.state.suggestions = []
    await user.click(await screen.findByRole('button', { name: 'Ajouter Café' }))

    await waitFor(() => expect(screen.queryByTestId('grocery-suggestions')).toBeNull())
  })

  test('a product without usual quantity is added by its name only', async () => {
    const api = stubApi({ suggestions: [{ ...cafe, usualQuantity: null, usualUnit: null }] })
    await open()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Ajouter Café' }))

    await waitFor(() => expect(api.calls('POST', '/grocery/add-item')).toHaveLength(1))
    expect(api.calls('POST', '/grocery/add-item')[0].body).toEqual({ label: 'Café' })
  })

  test('a refused addition says so and keeps the row', async () => {
    stubApi({ writeStatus: 400 })
    await open()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Ajouter Café' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringContaining('Café'), { type: 'error' }))
    expect(screen.getByText('Café · toutes les 2 sem.')).toBeInTheDocument()
  })

  test('« Pas besoin » snoozes the product and the row leaves', async () => {
    const api = stubApi()
    await open()
    api.state.suggestions = []
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Pas besoin de Café' }))

    await waitFor(() => expect(api.calls('POST', '/snooze')).toHaveLength(1))
    expect(api.calls('POST', '/snooze')[0].url).toMatch(new RegExp(`/api/grocery-suggestions/${CAFE}/snooze$`))
    expect(api.calls('POST', '/snooze')[0].body).toBeUndefined()
    await waitFor(() => expect(screen.queryByTestId('grocery-suggestions')).toBeNull())
    expect(api.calls('POST', '/grocery/add-item')).toHaveLength(0)
  })

  test('a failed « Pas besoin » says so and keeps the row', async () => {
    stubApi({ failWrites: true })
    await open()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Pas besoin de Café' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringContaining('Café'), { type: 'error' }))
    expect(screen.getByText('Café · toutes les 2 sem.')).toBeInTheDocument()
  })

  describe('live updates (Mercure)', () => {
    const stream = () => eventSources.find((source) => source.url.includes('grocery_lists'))!
    const emit = (payload: Record<string, unknown>) =>
      act(() => {
        stream().onmessage?.({ data: JSON.stringify(payload) } as MessageEvent)
      })

    test('listens to the products and the errands on the list stream, without a stream of its own', async () => {
      stubApi()
      await open()

      const url = decodeURIComponent(stream().url)
      expect(url).toContain('/users/user-1/api/products/:id')
      expect(url).toContain('/users/user-1/api/errands/:id')
      expect(eventSources).toHaveLength(1)
    })

    test.each([
      ['a product', { '@id': `/api/products/${CAFE}`, suggestionSnoozedUntil: '2026-10-30' }],
      ['an errand', { '@id': '/api/errands/01ERRAND' }],
      ['a recurring item', { '@id': '/api/recurring_grocery_items/R1', label: 'Lait', frequency: 'weekly' }],
      ['the list', { '@id': '/api/grocery_lists/list-1' }],
    ])("%s's update reads the suggestions again", async (_name, payload) => {
      const api = stubApi()
      await open()
      await screen.findByText('Café · toutes les 2 sem.')
      const before = api.suggestionReads()

      api.state.suggestions = []
      emit(payload)

      await waitFor(() => expect(api.suggestionReads()).toBe(before + 1))
      await waitFor(() => expect(screen.queryByTestId('grocery-suggestions')).toBeNull())
    })

    test("a product's update does not read the list again", async () => {
      const api = stubApi()
      await open()
      const listReads = mockGetOne.mock.calls.length
      const before = api.suggestionReads()

      emit({ '@id': `/api/products/${CAFE}`, suggestionSnoozedUntil: null })

      await waitFor(() => expect(api.suggestionReads()).toBe(before + 1))
      expect(mockGetOne.mock.calls.length).toBe(listReads)
      expect(mockGetList.mock.calls.length).toBe(1)
    })
  })
})
