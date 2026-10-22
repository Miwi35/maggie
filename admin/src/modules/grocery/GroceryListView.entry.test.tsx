import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GroceryListView } from './GroceryListView'

// MAG-573 — the add and edit dialogs take either a quantity or a number of packagings.

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
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

const pasta = {
  id: 'p-pasta',
  name: 'Pâtes sèches',
  category: 'grain',
  packagingUnit: 'pack',
  packagingSize: 500,
  packagingSizeUnit: 'g',
  restockQuantity: 2,
}
const rice = { ...pasta, id: 'p-rice', name: 'Riz', restockQuantity: null }
const courgettes = { id: 'p-courgettes', name: 'Courgettes', category: 'produce', defaultUnit: 'piece' }

const sampleList = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [
    {
      id: 'item-riz',
      '@id': '/.well-known/genid/item-riz',
      label: 'Riz',
      quantity: 1,
      unit: 'pack',
      checked: false,
      source: 'manual',
      store: { id: 'store-1', name: 'Supermarché', visitOrder: 1 },
      position: 0,
      product: rice,
    },
  ],
}

const requests = (mockFetch: ReturnType<typeof vi.fn>, method: string) =>
  mockFetch.mock.calls
    .filter(([, init]) => init?.method === method)
    .map(([url, init]) => ({ url: String(url), body: JSON.parse(init.body as string) }))

describe('GroceryListView — quantity or packagings (MAG-573)', () => {
  let mockFetch: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.restoreAllMocks()
    mockNotify.mockClear()
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    mockGetList.mockImplementation((resource: string) => {
      if (resource === 'products') return Promise.resolve({ data: [pasta, rice, courgettes], total: 3 })
      if (resource === 'stores') return Promise.resolve({ data: [], total: 0 })
      return Promise.resolve({ data: [sampleList], total: 1 })
    })
    mockGetOne.mockResolvedValue({ data: sampleList })
    mockFetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({}) })
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    localStorage.removeItem('user')
  })

  const openAdd = async (label: string) => {
    const user = userEvent.setup()
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ajouter' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Ajouter' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Article'), label)

    return { user, dialog }
  }

  test('a packaged product opens in Conditionnement, filled with its automatic restock', async () => {
    const { dialog } = await openAdd('Pâtes sèches')

    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(2))
    expect(within(dialog).getByTestId('packaging-caption')).toHaveTextContent('paquets de 500 g')
    expect(within(dialog).getByTestId('prefilled-chip')).toHaveTextContent('prérempli · recharge auto')
    expect(within(dialog).getByTestId('quantity-conversion')).toHaveTextContent('= 1 kg')
  })

  test('without restock quantity it starts at one packaging, with no prefilled badge', async () => {
    const { dialog } = await openAdd('Riz')

    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(1))
    expect(within(dialog).queryByTestId('prefilled-chip')).not.toBeInTheDocument()
  })

  test('a product without packaging offers a quantity alone, in its usual unit', async () => {
    const { dialog } = await openAdd('Courgettes')

    await waitFor(() => expect(within(dialog).getByLabelText('Quantité')).toHaveValue(null))
    expect(within(dialog).queryByRole('button', { name: 'Conditionnement' })).not.toBeInTheDocument()
    expect(within(dialog).queryByLabelText(/Nombre de/)).not.toBeInTheDocument()
  })

  test('the stepper moves by one packaging and the badge goes once it is touched', async () => {
    const { user, dialog } = await openAdd('Pâtes sèches')
    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(2))

    await user.click(within(dialog).getByRole('button', { name: 'Augmenter le nombre de conditionnements' }))

    expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(3)
    expect(within(dialog).queryByTestId('prefilled-chip')).not.toBeInTheDocument()
    expect(within(dialog).getByTestId('quantity-conversion')).toHaveTextContent('= 1,5 kg')
  })

  test('adding in Conditionnement sends the packagings and no quantity', async () => {
    const { user, dialog } = await openAdd('Pâtes sèches')
    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(2))

    await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

    await waitFor(() => expect(requests(mockFetch, 'POST')).toHaveLength(1))
    const { body } = requests(mockFetch, 'POST')[0]
    expect(body).toMatchObject({ label: 'Pâtes sèches', packagings: 2 })
    expect(body).not.toHaveProperty('quantity')
    expect(body).not.toHaveProperty('unit')
  })

  test('switching to Quantité shows the conversion and sends the quantity alone', async () => {
    const { user, dialog } = await openAdd('Pâtes sèches')
    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toBeInTheDocument())

    await user.click(within(dialog).getByRole('button', { name: 'Quantité' }))
    await user.type(within(dialog).getByLabelText('Quantité'), '300')
    await user.click(within(dialog).getByLabelText('Unité'))
    await user.click(await screen.findByRole('option', { name: 'g' }))

    expect(within(dialog).getByTestId('quantity-conversion')).toHaveTextContent('= 1 paquet de 500 g à acheter')

    await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

    await waitFor(() => expect(requests(mockFetch, 'POST')).toHaveLength(1))
    const { body } = requests(mockFetch, 'POST')[0]
    expect(body).toMatchObject({ label: 'Pâtes sèches', quantity: 300, unit: 'g' })
    expect(body).not.toHaveProperty('packagings')
  })

  test('a number of packagings that is not a whole number blocks the add button', async () => {
    const { user, dialog } = await openAdd('Pâtes sèches')
    const field = await within(dialog).findByLabelText('Nombre de paquets')

    await user.clear(field)
    await user.type(field, '1.5')

    expect(within(dialog).getByRole('button', { name: 'Ajouter' })).toBeDisabled()
  })

  test('a refusal of the API is told and keeps the dialog open', async () => {
    mockFetch.mockResolvedValue({ ok: false, status: 400, json: () => Promise.resolve({ error: 'no' }) })
    const { user, dialog } = await openAdd('Pâtes sèches')
    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toBeInTheDocument())

    await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith("Erreur : l'article n'a pas été ajouté", { type: 'error' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  test('editing a packaged line in Quantité sends the quantity and its unit alone', async () => {
    const user = userEvent.setup()
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Riz')).toBeInTheDocument())
    await user.click(screen.getByText('Riz'))
    const dialog = await screen.findByRole('dialog')

    expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(1)
    await user.click(within(dialog).getByRole('button', { name: 'Quantité' }))
    await user.type(within(dialog).getByLabelText('Quantité'), '600')
    await user.click(within(dialog).getByLabelText('Unité'))
    await user.click(await screen.findByRole('option', { name: 'g' }))
    expect(within(dialog).getByTestId('quantity-conversion')).toHaveTextContent('= 2 paquets de 500 g à acheter')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(requests(mockFetch, 'PATCH')).toHaveLength(1))
    const { body } = requests(mockFetch, 'PATCH')[0]
    expect(body).toMatchObject({ quantity: 600, unit: 'g' })
    expect(body).not.toHaveProperty('packagings')
  })

  test('saving an edit without touching the quantity sends none', async () => {
    const user = userEvent.setup()
    render(<GroceryListView />)
    await waitFor(() => expect(screen.getByText('Riz')).toBeInTheDocument())
    await user.click(screen.getByText('Riz'))
    const dialog = await screen.findByRole('dialog')

    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(requests(mockFetch, 'PATCH')).toHaveLength(1))
    const { body } = requests(mockFetch, 'PATCH')[0]
    expect(body).not.toHaveProperty('packagings')
    expect(body).not.toHaveProperty('quantity')
  })
})
