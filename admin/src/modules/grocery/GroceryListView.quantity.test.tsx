import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GroceryListView } from './GroceryListView'

// MAG-291 — − / + and direct entry of a quantity on the line itself.

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
// One object for every render: a fresh one would re-run the view's fetch effect
// and put the list back to its initial state after each change.
const dataProvider = { getList: mockGetList, getOne: mockGetOne }
vi.mock('react-admin', () => ({
  useDataProvider: () => dataProvider,
  useNotify: () => mockNotify,
  Title: ({ title }: { title: string }) => <span>{title}</span>,
}))

const line = (id: string, label: string, quantity: number | undefined, unit: string, position: number) => ({
  id,
  '@id': `/.well-known/genid/${id}`,
  label,
  quantity,
  unit,
  checked: false,
  source: 'manual',
  store: { id: 'store-1', name: 'Supermarché', visitOrder: 1 },
  position,
})

const rice = { id: 'product-riz', packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g' }
const jam = { id: 'product-confiture', packagingUnit: 'jar', packagingSize: null, packagingSizeUnit: null }

const packaged = (id: string, label: string, quantity: number, unit: string | undefined, product: object, position: number) => ({
  ...line(id, label, quantity, unit ?? '', position),
  unit,
  product,
})

const list = (items: object[]) => ({ id: 'list-1', '@id': '/api/grocery_lists/list-1', items })

const sampleList = list([
  line('item-riz', 'Riz', 1, 'pack', 0),
  line('item-lait', 'Lait', 1, 'l', 1),
  line('item-farine', 'Farine', 500, 'g', 2),
  line('item-sel', 'Sel', undefined, '', 3),
])

const row = (label: string) => screen.getByText(label).closest('[data-testid="grocery-item"]') as HTMLElement

const patches = (mockFetch: ReturnType<typeof vi.fn>) =>
  mockFetch.mock.calls
    .filter(([, init]) => init?.method === 'PATCH')
    .map(([url, init]) => ({ url: String(url), body: JSON.parse(init.body as string) }))

describe('GroceryListView — quantity on the line', () => {
  let mockFetch: ReturnType<typeof vi.fn>

  const mockApi = (response: { ok: boolean; status: number } = { ok: true, status: 200 }) => {
    mockFetch = vi.fn().mockResolvedValue({ ...response, json: () => Promise.resolve({}) })
    vi.stubGlobal('fetch', mockFetch)
  }

  const open = async () => {
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Riz')).toBeInTheDocument())
  }

  beforeEach(() => {
    vi.restoreAllMocks()
    eventSources.length = 0
    mockNotify.mockClear()
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    mockGetList.mockResolvedValue({ data: [sampleList], total: 1 })
    mockGetOne.mockResolvedValue({ data: sampleList })
    mockApi()
  })

  afterEach(() => {
    localStorage.removeItem('user')
  })

  test('shows the quantity with its unit, between − and +', async () => {
    await open()

    expect(within(row('Riz')).getByText('1 paquet')).toBeInTheDocument()
    expect(within(row('Farine')).getByText('500 g')).toBeInTheDocument()
    expect(within(row('Riz')).getByRole('button', { name: 'Diminuer la quantité de Riz' })).toBeInTheDocument()
    expect(within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' })).toBeInTheDocument()
  })

  test('two taps on + show 3 at once and send a single request with 3', async () => {
    await open()
    const user = userEvent.setup()
    const plus = within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' })

    await user.click(plus)
    await user.click(plus)

    expect(within(row('Riz')).getByText('3 paquets')).toBeInTheDocument()
    expect(patches(mockFetch)).toHaveLength(0)

    await waitFor(() => expect(patches(mockFetch)).toHaveLength(1))
    expect(patches(mockFetch)[0].url).toMatch(/\/api\/grocery\/edit-item\/item-riz$/)
    expect(patches(mockFetch)[0].body).toEqual({ quantity: 3 })
    expect(within(row('Riz')).getByText('3 paquets')).toBeInTheDocument()
  })

  test('taps on + and − are one request carrying the net quantity', async () => {
    await open()
    const user = userEvent.setup()

    await user.click(within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' }))
    await user.click(within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' }))
    await user.click(within(row('Riz')).getByRole('button', { name: 'Diminuer la quantité de Riz' }))

    await waitFor(() => expect(patches(mockFetch)).toHaveLength(1))
    expect(patches(mockFetch)[0].body).toEqual({ quantity: 2 })
  })

  test('the step follows the unit: 100 g for grams, 0.1 for litres', async () => {
    await open()
    const user = userEvent.setup()

    await user.click(within(row('Farine')).getByRole('button', { name: 'Augmenter la quantité de Farine' }))
    await user.click(within(row('Lait')).getByRole('button', { name: 'Augmenter la quantité de Lait' }))

    await waitFor(() => expect(patches(mockFetch)).toHaveLength(2))
    const byItem = Object.fromEntries(patches(mockFetch).map((p) => [p.url.split('/').pop(), p.body.quantity]))
    expect(byItem).toEqual({ 'item-farine': 600, 'item-lait': 1.1 })
  })

  test('− at the minimum is disabled: it never removes the line', async () => {
    await open()

    expect(within(row('Riz')).getByRole('button', { name: 'Diminuer la quantité de Riz' })).toBeDisabled()
    expect(within(row('Sel')).getByRole('button', { name: 'Diminuer la quantité de Sel' })).toBeDisabled()
    expect(screen.getByText('Riz')).toBeInTheDocument()
  })

  test('+ on a line without quantity starts it at one step', async () => {
    await open()

    await userEvent.setup().click(within(row('Sel')).getByRole('button', { name: 'Augmenter la quantité de Sel' }))

    await waitFor(() => expect(patches(mockFetch)).toHaveLength(1))
    expect(patches(mockFetch)[0].body).toEqual({ quantity: 1 })
  })

  test('typing a quantity and pressing Enter sends it', async () => {
    await open()
    const user = userEvent.setup()

    await user.click(within(row('Riz')).getByRole('button', { name: 'Modifier la quantité de Riz' }))
    const input = screen.getByRole('textbox', { name: 'Quantité de Riz' })
    await user.clear(input)
    await user.type(input, '7{Enter}')

    expect(within(row('Riz')).getByText('7 paquets')).toBeInTheDocument()
    await waitFor(() => expect(patches(mockFetch)).toHaveLength(1))
    expect(patches(mockFetch)[0].body).toEqual({ quantity: 7 })
  })

  test('typing a quantity and leaving the field sends it, a comma being a decimal point', async () => {
    await open()
    const user = userEvent.setup()

    await user.click(within(row('Lait')).getByRole('button', { name: 'Modifier la quantité de Lait' }))
    const input = screen.getByRole('textbox', { name: 'Quantité de Lait' })
    await user.clear(input)
    await user.type(input, '2,5')
    await user.tab()

    await waitFor(() => expect(patches(mockFetch)).toHaveLength(1))
    expect(patches(mockFetch)[0].body).toEqual({ quantity: 2.5 })
  })

  test.each([
    ['empty', ''],
    ['zero', '0'],
    ['negative', '-3'],
    ['not a number', 'abc'],
  ])('an invalid quantity (%s) is refused and nothing is sent', async (_name, typed) => {
    await open()
    const user = userEvent.setup()

    await user.click(within(row('Riz')).getByRole('button', { name: 'Modifier la quantité de Riz' }))
    const input = screen.getByRole('textbox', { name: 'Quantité de Riz' })
    await user.clear(input)
    if (typed) await user.type(input, typed)
    await user.keyboard('{Enter}')

    expect(mockNotify).toHaveBeenCalledWith(expect.stringContaining('Quantité invalide'), { type: 'warning' })
    expect(within(row('Riz')).getByText('1 paquet')).toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 600))
    expect(patches(mockFetch)).toHaveLength(0)
  })

  test('Escape leaves the field without sending anything', async () => {
    await open()
    const user = userEvent.setup()

    await user.click(within(row('Riz')).getByRole('button', { name: 'Modifier la quantité de Riz' }))
    await user.type(screen.getByRole('textbox', { name: 'Quantité de Riz' }), '9{Escape}')

    expect(within(row('Riz')).getByText('1 paquet')).toBeInTheDocument()
    expect(mockNotify).not.toHaveBeenCalled()
    await new Promise((resolve) => setTimeout(resolve, 600))
    expect(patches(mockFetch)).toHaveLength(0)
  })

  test('an API refusal puts the previous quantity back and says so', async () => {
    mockApi({ ok: false, status: 400 })
    await open()

    await userEvent.setup().click(within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' }))
    expect(within(row('Riz')).getByText('2 paquets')).toBeInTheDocument()

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringContaining('Riz'), { type: 'error' }))
    expect(within(row('Riz')).getByText('1 paquet')).toBeInTheDocument()
  })

  test('a network failure puts the previous quantity back and says so', async () => {
    mockFetch = vi.fn().mockRejectedValue(new Error('offline'))
    vi.stubGlobal('fetch', mockFetch)
    await open()

    await userEvent.setup().click(within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
    expect(within(row('Riz')).getByText('1 paquet')).toBeInTheDocument()
  })

  test("another window's change arrives through Mercure", async () => {
    await open()
    expect(eventSources.length).toBeGreaterThan(0)

    const changed = list([
      line('item-riz', 'Riz', 4, 'pack', 0),
      line('item-lait', 'Lait', 1, 'l', 1),
      line('item-farine', 'Farine', 500, 'g', 2),
      line('item-sel', 'Sel', undefined, '', 3),
    ])
    act(() => {
      eventSources[0].onmessage?.({ data: JSON.stringify(changed) } as MessageEvent)
    })

    await waitFor(() => expect(within(row('Riz')).getByText('4 paquets')).toBeInTheDocument())
  })

  test('opening the line to edit it is not triggered by the quantity buttons', async () => {
    await open()

    await userEvent.setup().click(within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  describe('counted in packagings (MAG-299)', () => {
    const packagedList = list([
      packaged('item-riz', 'Riz', 1, 'pack', rice, 0),
      packaged('item-confiture', 'Confiture', 2, 'jar', jam, 1),
      packaged('item-semoule', 'Semoule', 2, undefined, rice, 2),
      packaged('item-riz-g', 'Riz basmati', 300, 'g', rice, 3),
    ])

    beforeEach(() => {
      mockGetList.mockResolvedValue({ data: [packagedList], total: 1 })
      mockGetOne.mockResolvedValue({ data: packagedList })
    })

    test('reads « 1 paquet » with what a pack holds after it, and nothing after a jar of unknown content', async () => {
      await open()

      expect(within(row('Riz')).getByText('1 paquet')).toBeInTheDocument()
      expect(within(row('Riz')).getByTestId('quantity-packaging')).toHaveTextContent('(500 g)')
      expect(within(row('Confiture')).getByText('2 bocaux')).toBeInTheDocument()
      expect(within(row('Confiture')).queryByTestId('quantity-packaging')).not.toBeInTheDocument()
    })

    test('a line with no unit of a packaged product reads as its packaging', async () => {
      await open()

      expect(within(row('Semoule')).getByText('2 paquets')).toBeInTheDocument()
      expect(within(row('Semoule')).getByTestId('quantity-packaging')).toHaveTextContent('(500 g)')
    })

    test('a line in grams keeps the free step and shows no pack content', async () => {
      await open()
      const user = userEvent.setup()

      expect(within(row('Riz basmati')).getByText('300 g')).toBeInTheDocument()
      expect(within(row('Riz basmati')).queryByTestId('quantity-packaging')).not.toBeInTheDocument()

      await user.click(within(row('Riz basmati')).getByRole('button', { name: 'Augmenter la quantité de Riz basmati' }))
      await waitFor(() => expect(patches(mockFetch)).toHaveLength(1))
      expect(patches(mockFetch)[0].body).toEqual({ quantity: 400 })
    })

    test('+ adds one pack and − takes one off: « 3 paquets » after two taps, one request', async () => {
      await open()
      const user = userEvent.setup()
      const plus = within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' })

      await user.click(plus)
      await user.click(plus)

      expect(within(row('Riz')).getByText('3 paquets')).toBeInTheDocument()
      await waitFor(() => expect(patches(mockFetch)).toHaveLength(1))
      expect(patches(mockFetch)[0].body).toEqual({ quantity: 3 })

      await user.click(within(row('Confiture')).getByRole('button', { name: 'Diminuer la quantité de Confiture' }))
      expect(within(row('Confiture')).getByText('1 bocal')).toBeInTheDocument()
      await waitFor(() => expect(patches(mockFetch)).toHaveLength(2))
      expect(patches(mockFetch)[1].body).toEqual({ quantity: 1 })
    })

    test('the last pack cannot be taken off with −', async () => {
      await open()

      expect(within(row('Riz')).getByRole('button', { name: 'Diminuer la quantité de Riz' })).toBeDisabled()
    })

    test('an API refusal puts the previous packs back and says so', async () => {
      mockApi({ ok: false, status: 400 })
      await open()

      await userEvent.setup().click(within(row('Riz')).getByRole('button', { name: 'Augmenter la quantité de Riz' }))
      expect(within(row('Riz')).getByText('2 paquets')).toBeInTheDocument()

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringContaining('Riz'), { type: 'error' }))
      expect(within(row('Riz')).getByText('1 paquet')).toBeInTheDocument()
    })

    test('a Mercure update keeps the packaging of the line', async () => {
      await open()

      act(() => {
        eventSources[0].onmessage?.({
          data: JSON.stringify(list([packaged('item-riz', 'Riz', 4, 'pack', rice, 0)])),
        } as MessageEvent)
      })

      await waitFor(() => expect(within(row('Riz')).getByText('4 paquets')).toBeInTheDocument())
      expect(within(row('Riz')).getByTestId('quantity-packaging')).toHaveTextContent('(500 g)')
    })
  })
})
