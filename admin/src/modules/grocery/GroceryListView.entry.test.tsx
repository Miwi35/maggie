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

const noWeek = { productId: '', days: 7, recipeNeed: null, needs: [], meals: [] }
const riceWeek = {
  productId: 'p-rice',
  days: 7,
  recipeNeed: { quantity: 800, unit: 'g' },
  needs: [{ quantity: 800, unit: 'g' }],
  meals: [
    { mealId: 'meal-1', name: 'Risotto aux champignons', date: '2026-10-23', quantity: 500, unit: 'g' },
    { mealId: 'meal-2', name: 'Riz cantonais', date: '2026-10-25', quantity: 300, unit: 'g' },
  ],
}

const requests = (mockFetch: ReturnType<typeof vi.fn>, method: string) =>
  mockFetch.mock.calls
    .filter(([, init]) => init?.method === method)
    .map(([url, init]) => ({ url: String(url), body: JSON.parse(init.body as string) }))

describe('GroceryListView — quantity or packagings (MAG-573)', () => {
  let mockFetch: ReturnType<typeof vi.fn>
  // What the API answers to « what do the week's meals need of this product ».
  let week: object

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
    week = noWeek
    mockFetch = vi.fn().mockImplementation((url: string) =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(String(url).includes('/grocery/recipe-need/') ? week : {}) }),
    )
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
    expect(within(dialog).getByTestId('prefilled-chip')).toHaveTextContent('prérempli · réassort')
    expect(within(dialog).getByTestId('quantity-summary')).toHaveTextContent('1 kg · 2 paquets')
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
    expect(within(dialog).getByTestId('quantity-summary')).toHaveTextContent('1,5 kg · 3 paquets')
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

  test('switching to Quantité shows the summary and sends the quantity alone', async () => {
    const { user, dialog } = await openAdd('Pâtes sèches')
    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toBeInTheDocument())

    await user.click(within(dialog).getByRole('button', { name: 'Quantité' }))
    await user.type(within(dialog).getByLabelText('Quantité'), '300')
    await user.click(within(dialog).getByLabelText('Unité'))
    await user.click(await screen.findByRole('option', { name: 'g' }))

    expect(within(dialog).getByTestId('quantity-summary')).toHaveTextContent('300 g · 1 paquet')

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

  test('the message of a 400 is shown in the dialog, and goes once the quantity changes', async () => {
    mockFetch.mockImplementation((url: string, init?: { method?: string }) =>
      Promise.resolve(
        init?.method === 'POST'
          ? { ok: false, status: 400, json: () => Promise.resolve({ error: 'packagings must be a whole number of at least 1' }) }
          : { ok: true, status: 200, json: () => Promise.resolve(String(url).includes('recipe-need') ? noWeek : {}) },
      ),
    )
    const { user, dialog } = await openAdd('Pâtes sèches')
    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toBeInTheDocument())

    await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

    expect(await within(dialog).findByTestId('quantity-error')).toHaveTextContent('packagings must be a whole number of at least 1')

    await user.click(within(dialog).getByRole('button', { name: 'Augmenter le nombre de conditionnements' }))
    expect(within(dialog).queryByTestId('quantity-error')).not.toBeInTheDocument()
  })

  test('an addition that does not cover the week is told as a warning', async () => {
    mockFetch.mockImplementation((url: string, init?: { method?: string }) =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve(
            init?.method === 'POST' ? { success: true, coversWeek: false, packagingsToCover: 3 } : String(url).includes('recipe-need') ? noWeek : {},
          ),
      }),
    )
    const { user, dialog } = await openAdd('Pâtes sèches')
    await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toBeInTheDocument())

    await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

    await waitFor(() =>
      expect(mockNotify).toHaveBeenCalledWith(expect.stringContaining('ne couvre pas les besoins de la semaine'), { type: 'warning' }),
    )
  })

  describe('the recipes of the week (MAG-573)', () => {
    test('the box tells the need and the meals behind it once the product is known', async () => {
      week = riceWeek
      const { dialog } = await openAdd('Riz')

      const box = await within(dialog).findByTestId('recipe-need')
      expect(box).toHaveTextContent('Pour les recettes de la semaine · 800 g')
      expect(within(box).getByTestId('recipe-need-meals')).toHaveTextContent('Risotto aux champignons')
      expect(within(box).getByTestId('recipe-need-meals')).toHaveTextContent('Riz cantonais')
      expect(within(box).getByTestId('recipe-need-meals')).toHaveTextContent('500 g')
      expect(mockFetch.mock.calls.some(([url]) => String(url).endsWith('/grocery/recipe-need/p-rice'))).toBe(true)
    })

    test('no box when the meals need nothing, nor for a product without packaging', async () => {
      const { dialog } = await openAdd('Riz')
      await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toBeInTheDocument())
      expect(within(dialog).queryByTestId('recipe-need')).not.toBeInTheDocument()
      expect(mockFetch.mock.calls.filter(([url]) => String(url).includes('recipe-need'))).toHaveLength(1)
    })

    test('without restock the packagings start at the need rounded up, flagged as such', async () => {
      week = riceWeek
      const { dialog } = await openAdd('Riz')

      await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(2))
      expect(within(dialog).getByTestId('prefilled-chip')).toHaveTextContent('prérempli · besoins de la semaine')
      expect(within(dialog).getByTestId('quantity-summary')).toHaveTextContent('1 kg (dont 800 g pour les recettes) · 2 paquets')
      expect(within(dialog).queryByTestId('need-shortfall')).not.toBeInTheDocument()
    })

    test('a restock stays the prefill even when the week asks for more, and the alert offers to adjust', async () => {
      week = { ...riceWeek, recipeNeed: { quantity: 1200, unit: 'g' }, needs: [{ quantity: 1200, unit: 'g' }] }
      const { user, dialog } = await openAdd('Pâtes sèches')
      week = { ...riceWeek, recipeNeed: { quantity: 1200, unit: 'g' } }

      const alert = await within(dialog).findByTestId('need-shortfall')
      expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(2)
      expect(alert).toHaveTextContent('Ne couvre pas les besoins de la semaine (1,2 kg pour les recettes)')

      await user.click(within(alert).getByRole('button', { name: 'Ajuster à 3 paquets' }))

      expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(3)
      expect(within(dialog).queryByTestId('need-shortfall')).not.toBeInTheDocument()
      expect(within(dialog).queryByTestId('prefilled-chip')).not.toBeInTheDocument()
      expect(within(dialog).getByTestId('quantity-summary')).toHaveTextContent('1,5 kg (dont 1,2 kg pour les recettes) · 3 paquets')

      await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

      await waitFor(() => expect(requests(mockFetch, 'POST')).toHaveLength(1))
      expect(requests(mockFetch, 'POST')[0].body).toMatchObject({ packagings: 3 })
    })

    test('a quantity below the need alerts too, and the button brings it back to packagings', async () => {
      week = riceWeek
      const { user, dialog } = await openAdd('Riz')
      await waitFor(() => expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(2))

      await user.click(within(dialog).getByRole('button', { name: 'Quantité' }))
      await user.type(within(dialog).getByLabelText('Quantité'), '300')
      await user.click(within(dialog).getByLabelText('Unité'))
      await user.click(await screen.findByRole('option', { name: 'g' }))

      const alert = within(dialog).getByTestId('need-shortfall')
      expect(alert).toHaveTextContent('Ne couvre pas les besoins de la semaine (800 g pour les recettes)')

      await user.click(within(alert).getByRole('button', { name: 'Ajuster à 2 paquets' }))

      expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(2)
      expect(within(dialog).queryByTestId('need-shortfall')).not.toBeInTheDocument()
    })

    test('Quantité suggests the need and the restock, one tap fills the field', async () => {
      week = riceWeek
      const { user, dialog } = await openAdd('Pâtes sèches')
      await within(dialog).findByTestId('recipe-need')

      await user.click(within(dialog).getByRole('button', { name: 'Quantité' }))
      const offers = within(dialog).getByTestId('quantity-suggestions')
      expect(offers).toHaveTextContent('800 g · besoins')
      expect(offers).toHaveTextContent('1 kg · réassort')

      await user.click(within(offers).getByText('800 g · besoins'))

      expect(within(dialog).getByLabelText('Quantité')).toHaveValue(800)
      expect(within(dialog).getByTestId('quantity-summary')).toHaveTextContent('800 g (dont 800 g pour les recettes) · 2 paquets')
    })

    test('the edit dialog of a packaged line shows the week too, and the wanted quantity in Quantité', async () => {
      week = riceWeek
      const line = { ...sampleList.items[0], quantity: 3, desiredQuantity: 1400, desiredUnit: 'g' }
      const withDesired = { ...sampleList, items: [line] }
      mockGetList.mockImplementation((resource: string) => {
        if (resource === 'products') return Promise.resolve({ data: [pasta, rice, courgettes], total: 3 })
        if (resource === 'stores') return Promise.resolve({ data: [], total: 0 })
        return Promise.resolve({ data: [withDesired], total: 1 })
      })
      mockGetOne.mockResolvedValue({ data: withDesired })
      const user = userEvent.setup()
      render(<GroceryListView />)
      await waitFor(() => expect(screen.getByText('Riz')).toBeInTheDocument())
      expect(screen.getByTestId('needed-reminder')).toHaveTextContent('1,4 kg')
      await user.click(screen.getByText('Riz'))
      const dialog = await screen.findByRole('dialog')

      expect(await within(dialog).findByTestId('recipe-need')).toHaveTextContent('800 g')
      expect(within(dialog).getByLabelText('Nombre de paquets')).toHaveValue(3)
      await user.click(within(dialog).getByRole('button', { name: 'Quantité' }))
      expect(within(dialog).getByLabelText('Quantité')).toHaveValue(1400)
    })
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
    expect(within(dialog).getByTestId('quantity-summary')).toHaveTextContent('600 g · 2 paquets')
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
