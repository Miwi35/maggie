import { describe, test, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GroceryListView } from './GroceryListView'
import { stubRecurringApi } from '../../test/recurringApi'
import type { StubMember } from '../../test/recurringApi'

// MAG-369 — recurring items: the « Récurrents » tab and the repeat control on a line.

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

const LAIT = '01M3VA6TKHF1CB6GMPSRV8WE7R'
const PAIN = '01M3VA6TKHF1CB6GMPSRV8WE7S'

const line = (id: string, label: string, product: object | null, position: number) => ({
  id,
  '@id': `/.well-known/genid/${id}`,
  label,
  product,
  quantity: 1,
  unit: 'l',
  checked: false,
  source: 'manual',
  store: { id: 'store-1', name: 'Supermarché', visitOrder: 1 },
  position,
})

const sampleList = {
  id: 'list-1',
  '@id': '/api/grocery_lists/list-1',
  items: [
    line('item-lait', 'Lait', { '@id': `/api/products/${LAIT}`, id: `/api/products/${LAIT}` }, 0),
    line('item-pain', 'Pain', { id: PAIN }, 1),
    line('item-libre', 'Bougies', null, 2),
  ],
}

const products = [
  { id: `/api/products/${LAIT}`, name: 'Lait' },
  { id: `/api/products/${PAIN}`, name: 'Pain' },
]

const lait: StubMember = { id: 'R1', product: `/api/products/${LAIT}`, label: 'Lait', frequency: 'weekly', nextDueAt: '2099-01-08' }
const beurre: StubMember = { id: 'R2', product: null, label: 'Beurre', frequency: 'monthly', nextDueAt: '2099-02-01' }

const row = (label: string) => screen.getByText(label, { selector: '[data-testid="grocery-item"] *' }).closest('[data-testid="grocery-item"]') as HTMLElement

const open = async (initial: StubMember[] = [], options: Parameters<typeof stubRecurringApi>[1] = {}) => {
  const api = stubRecurringApi(initial, options)
  render(<GroceryListView />)
  await screen.findByText('Lait')
  return api
}

const openTab = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole('tab', { name: 'Récurrents' }))
  return screen.findByTestId('recurring-tab')
}

describe('GroceryListView — recurring items', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    eventSources.length = 0
    mockNotify.mockClear()
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('token', 'test-jwt')
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    mockGetList.mockImplementation((resource: string) =>
      Promise.resolve(
        resource === 'products' ? { data: products, total: products.length } : { data: [sampleList], total: 1 },
      ),
    )
    mockGetOne.mockResolvedValue({ data: sampleList })
  })

  describe('the « Récurrents » tab', () => {
    test('opens on the list, with the tab next to it', async () => {
      await open([lait])

      expect(screen.getByRole('tab', { name: 'Liste' })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByRole('tab', { name: 'Récurrents' })).toHaveAttribute('aria-selected', 'false')
      expect(screen.queryByTestId('recurring-tab')).toBeNull()
      expect(screen.getByText('Ma liste de courses')).toBeInTheDocument()
    })

    test('lists every recurring item with its frequency and its next due date', async () => {
      const user = userEvent.setup()
      await open([lait, beurre])

      const tab = await openTab(user)

      await waitFor(() => expect(within(tab).getAllByTestId('recurring-item')).toHaveLength(2))
      const [first, second] = within(tab).getAllByTestId('recurring-item')
      expect(within(first).getByText('Lait')).toBeInTheDocument()
      expect(within(first).getByText('Prochaine échéance : 08/01/2099')).toBeInTheDocument()
      expect(within(first).getByRole('combobox', { name: 'Fréquence de Lait' })).toHaveTextContent('Toutes les semaines')
      expect(within(second).getByText('Beurre')).toBeInTheDocument()
      expect(within(second).getByText('Prochaine échéance : 01/02/2099')).toBeInTheDocument()
      expect(within(second).getByRole('combobox', { name: 'Fréquence de Beurre' })).toHaveTextContent('Tous les mois')
      expect(screen.queryByText('Ma liste de courses')).toBeNull()
    })

    test('says so when nothing is recurring', async () => {
      const user = userEvent.setup()
      await open([])

      const tab = await openTab(user)

      expect(await within(tab).findByText(/Aucun article récurrent/)).toBeInTheDocument()
    })

    test('adds a product with a frequency, weekly by default', async () => {
      const user = userEvent.setup()
      const api = await open([])
      const tab = await openTab(user)

      await user.click(within(tab).getByRole('combobox', { name: 'Produit à répéter' }))
      await user.click(await screen.findByRole('option', { name: 'Pain' }))
      expect(within(tab).getByRole('combobox', { name: 'Fréquence du nouvel article' })).toHaveTextContent('Toutes les semaines')
      await user.click(within(tab).getByRole('button', { name: 'Ajouter' }))

      await waitFor(() => expect(api.writes).toHaveLength(1))
      expect(api.writes[0]).toMatchObject({ method: 'POST', body: { product: `/api/products/${PAIN}`, frequency: 'weekly' } })
      expect(await within(tab).findByTestId('recurring-item')).toHaveTextContent('Produit ' + PAIN)
    })

    test('proposes only the products that are not recurring yet', async () => {
      const user = userEvent.setup()
      await open([lait])
      const tab = await openTab(user)
      await within(tab).findByTestId('recurring-item')

      await user.click(within(tab).getByRole('combobox', { name: 'Produit à répéter' }))

      expect(await screen.findByRole('option', { name: 'Pain' })).toBeInTheDocument()
      expect(screen.queryByRole('option', { name: 'Lait' })).toBeNull()
    })

    test('changes the frequency of an item', async () => {
      const user = userEvent.setup()
      const api = await open([lait])
      const tab = await openTab(user)

      await user.click(await within(tab).findByRole('combobox', { name: 'Fréquence de Lait' }))
      await user.click(await screen.findByRole('option', { name: 'Tous les mois' }))

      await waitFor(() => expect(api.writes).toHaveLength(1))
      expect(api.writes[0]).toMatchObject({ method: 'PATCH', url: expect.stringMatching(/\/recurring_grocery_items\/R1$/), body: { frequency: 'monthly' } })
      await waitFor(() =>
        expect(within(tab).getByRole('combobox', { name: 'Fréquence de Lait' })).toHaveTextContent('Tous les mois'),
      )
    })

    test('removes an item', async () => {
      const user = userEvent.setup()
      const api = await open([lait, beurre])
      const tab = await openTab(user)

      await user.click(await within(tab).findByRole('button', { name: 'Retirer Beurre des articles récurrents' }))

      await waitFor(() => expect(within(tab).queryByText('Beurre')).toBeNull())
      expect(api.writes).toEqual([expect.objectContaining({ method: 'DELETE', url: expect.stringMatching(/\/R2$/) })])
      expect(within(tab).getByText('Lait')).toBeInTheDocument()
    })

    test('an API refusal says so and leaves the item as it was', async () => {
      const user = userEvent.setup()
      const api = await open([lait], { failWrites: true })
      const tab = await openTab(user)

      await user.click(await within(tab).findByRole('button', { name: 'Retirer Lait des articles récurrents' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringContaining('Lait'), { type: 'error' }))
      expect(api.writes).toHaveLength(1)
      expect(within(tab).getByText('Lait')).toBeInTheDocument()
    })

    test('an API refusal on add keeps the choice and adds nothing', async () => {
      const user = userEvent.setup()
      await open([], { failWrites: true })
      const tab = await openTab(user)

      await user.click(within(tab).getByRole('combobox', { name: 'Produit à répéter' }))
      await user.click(await screen.findByRole('option', { name: 'Pain' }))
      await user.click(within(tab).getByRole('button', { name: 'Ajouter' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
      expect(within(tab).queryByTestId('recurring-item')).toBeNull()
      expect(within(tab).getByRole('combobox', { name: 'Produit à répéter' })).toHaveValue('Pain')
    })

    test('says it cannot read the items when the API fails', async () => {
      const user = userEvent.setup()
      await open([lait], { failReads: true })

      const tab = await openTab(user)

      expect(await within(tab).findByText('Impossible de lire les articles récurrents.')).toBeInTheDocument()
    })
  })

  describe('the repeat control on a line', () => {
    test('a line without recurrence offers to make it recurring; one without a product has no control', async () => {
      await open([])

      expect(within(row('Lait')).getByRole('button', { name: 'Rendre Lait récurrent' })).toBeInTheDocument()
      expect(within(row('Bougies')).queryByRole('button', { name: /récurr/i })).toBeNull()
    })

    test('setting it creates the recurring item and the line shows its frequency', async () => {
      const user = userEvent.setup()
      const api = await open([])

      await user.click(within(row('Lait')).getByRole('button', { name: 'Rendre Lait récurrent' }))
      await user.click(await screen.findByRole('menuitem', { name: 'Toutes les semaines' }))

      await waitFor(() => expect(api.writes).toHaveLength(1))
      expect(api.writes[0]).toMatchObject({ method: 'POST', body: { product: `/api/products/${LAIT}`, frequency: 'weekly' } })
      expect(
        await within(row('Lait')).findByRole('button', { name: 'Récurrence de Lait : toutes les semaines' }),
      ).toBeInTheDocument()
      // The product comes through Mercure as a bare id, the REST API as an IRI: both reach the same item.
      await user.click(within(row('Pain')).getByRole('button', { name: 'Rendre Pain récurrent' }))
      await user.click(await screen.findByRole('menuitem', { name: 'Tous les mois' }))
      await waitFor(() => expect(api.writes[1].body).toEqual({ product: `/api/products/${PAIN}`, frequency: 'monthly' }))
    })

    test('changing it patches the existing item instead of creating a second one', async () => {
      const user = userEvent.setup()
      const api = await open([lait])

      await user.click(await within(row('Lait')).findByRole('button', { name: /Récurrence de Lait/ }))
      await user.click(await screen.findByRole('menuitem', { name: 'Toutes les 2 semaines' }))

      await waitFor(() => expect(api.writes).toHaveLength(1))
      expect(api.writes[0]).toMatchObject({ method: 'PATCH', body: { frequency: 'biweekly' } })
      expect(
        await within(row('Lait')).findByRole('button', { name: 'Récurrence de Lait : toutes les 2 semaines' }),
      ).toBeInTheDocument()
    })

    test('never creates a second item when the screen has not heard of the first yet', async () => {
      const user = userEvent.setup()
      const api = await open([])
      // Created elsewhere, no Mercure event received here.
      api.members.push(lait)

      await user.click(within(row('Lait')).getByRole('button', { name: 'Rendre Lait récurrent' }))
      await user.click(await screen.findByRole('menuitem', { name: 'Tous les mois' }))

      await waitFor(() => expect(api.writes).toHaveLength(1))
      expect(api.writes[0].method).toBe('PATCH')
    })

    test('choosing « Non » deletes the item and the indicator goes away', async () => {
      const user = userEvent.setup()
      const api = await open([lait])

      await user.click(await within(row('Lait')).findByRole('button', { name: /Récurrence de Lait/ }))
      await user.click(await screen.findByRole('menuitem', { name: 'Non' }))

      await waitFor(() => expect(api.writes).toEqual([expect.objectContaining({ method: 'DELETE' })]))
      expect(await within(row('Lait')).findByRole('button', { name: 'Rendre Lait récurrent' })).toBeInTheDocument()
      expect(within(row('Lait')).queryByRole('button', { name: /Récurrence de Lait/ })).toBeNull()
    })

    test('removing the item from the tab clears the indicator on the line', async () => {
      const user = userEvent.setup()
      await open([lait])
      await within(row('Lait')).findByRole('button', { name: /Récurrence de Lait/ })

      const tab = await openTab(user)
      await user.click(await within(tab).findByRole('button', { name: 'Retirer Lait des articles récurrents' }))
      await waitFor(() => expect(within(tab).queryByText('Prochaine échéance : 08/01/2099')).toBeNull())
      await user.click(screen.getByRole('tab', { name: 'Liste' }))

      expect(await within(row('Lait')).findByRole('button', { name: 'Rendre Lait récurrent' })).toBeInTheDocument()
    })

    test('an API refusal says so and the line keeps what it showed', async () => {
      const user = userEvent.setup()
      await open([], { failWrites: true })

      await user.click(within(row('Lait')).getByRole('button', { name: 'Rendre Lait récurrent' }))
      await user.click(await screen.findByRole('menuitem', { name: 'Toutes les semaines' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
      expect(within(row('Lait')).getByRole('button', { name: 'Rendre Lait récurrent' })).toBeInTheDocument()
    })

    test('the source chip of a line that came from a recurring item stays', async () => {
      const recurringLine = { ...sampleList.items[0], source: 'recurring' }
      mockGetOne.mockResolvedValue({ data: { ...sampleList, items: [recurringLine] } })
      await open([lait])

      expect(within(row('Lait')).getByText('Récurrent')).toBeInTheDocument()
      expect(await within(row('Lait')).findByRole('button', { name: /Récurrence de Lait/ })).toBeInTheDocument()
    })
  })

  describe('live updates (Mercure)', () => {
    const recurringSource = () => eventSources.find((source) => source.url.includes('recurring_grocery_items'))

    test('subscribes to the recurring items of the user', async () => {
      await open([])

      expect(decodeURIComponent(recurringSource()!.url)).toContain('/users/user-1/api/recurring_grocery_items/:id')
    })

    test('an item created elsewhere shows on the line and in the tab', async () => {
      const user = userEvent.setup()
      const api = await open([])
      const tab = await openTab(user)
      await within(tab).findByText(/Aucun article récurrent/)

      api.members.push(lait)
      act(() => {
        recurringSource()!.onmessage?.({ data: JSON.stringify({ label: 'Lait', frequency: 'weekly' }) } as MessageEvent)
      })

      expect(await within(tab).findByText('Prochaine échéance : 08/01/2099')).toBeInTheDocument()
      await user.click(screen.getByRole('tab', { name: 'Liste' }))
      expect(await within(row('Lait')).findByRole('button', { name: /Récurrence de Lait/ })).toBeInTheDocument()
    })

    test('an item deleted elsewhere clears the indicator', async () => {
      const api = await open([lait])
      await within(row('Lait')).findByRole('button', { name: /Récurrence de Lait/ })

      api.members.length = 0
      act(() => {
        recurringSource()!.onmessage?.({ data: JSON.stringify({ deleted: true }) } as MessageEvent)
      })

      expect(await within(row('Lait')).findByRole('button', { name: 'Rendre Lait récurrent' })).toBeInTheDocument()
    })
  })
})
