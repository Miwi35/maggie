import { beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { stubAislesApi } from '../../test/aislesApi'
import type { StubAisle } from '../../test/aislesApi'
import { STORES_PAGE_TOPICS } from './aislesApi'
import { StoreList } from './StoreList'

const streams = new Set<string>()
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (topics: string[]) => {
    streams.add(JSON.stringify(topics))
  },
}))

const STORES = [
  { id: '/api/stores/biocoop', name: 'Biocoop', description: null, visitOrder: 2 },
  { id: '/api/stores/carrefour', name: 'Carrefour', description: null, visitOrder: 1 },
]
const AISLES: StubAisle[] = [
  { id: 'fruits', store: 'carrefour', name: 'Fruits et légumes', position: 1 },
  { id: 'dairy', store: 'carrefour', name: 'Crèmerie', position: 2 },
  { id: 'bakery', store: 'carrefour', name: 'Boulangerie', position: 3 },
  { id: 'vrac', store: 'biocoop', name: 'Vrac', position: 1 },
]
const PRODUCTS: Record<string, number> = { carrefour: 57, biocoop: 1 }

function renderStores(listed = STORES, overrides: Partial<DataProvider> = {}) {
  const aisles = stubAislesApi(AISLES)
  const aislesFetch = aisles.fetchMock
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), 'http://localhost')
      if (url.pathname.endsWith('/products')) {
        const total = PRODUCTS[url.searchParams.get('store') ?? ''] ?? 0
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ totalItems: total, member: [] }) } as Response)
      }
      return aislesFetch(input, init)
    }),
  )
  const dataProvider = testDataProvider({
    getList: (() => Promise.resolve({ data: listed, total: listed.length })) as unknown as DataProvider['getList'],
    ...overrides,
  })

  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider}>
        <ResourceContextProvider value="stores">
          <StoreList />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

  return { aisles, dataProvider }
}

const rows = () => screen.getAllByTestId('store-row')
const aisleNames = () => screen.getAllByTestId('store-aisle').map((row) => row.getAttribute('data-aisle'))

describe('StoreList — the stores page', () => {
  beforeEach(() => {
    streams.clear()
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt')
  })

  test('lists the stores in the order of the tour, each with its aisle and product counts', async () => {
    renderStores()

    await screen.findByText('3 rayons · 57 produits')
    expect(rows().map((row) => row.getAttribute('data-store'))).toEqual(['Carrefour', 'Biocoop'])
    expect(within(rows()[0]).getByText('3 rayons · 57 produits')).toBeInTheDocument()
    expect(within(rows()[1]).getByText('1 rayon · 1 produit')).toBeInTheDocument()
  })

  test('opens the first store with its aisles in the order of the way through it', async () => {
    renderStores()

    await waitFor(() => expect(aisleNames()).toEqual(['Fruits et légumes', 'Crèmerie', 'Boulangerie']))
    expect(screen.getByRole('textbox', { name: 'Nom du magasin' })).toHaveValue('Carrefour')
    expect(screen.getByText('Ordre du parcours')).toBeInTheDocument()
  })

  test('selecting another store shows its own aisles', async () => {
    const user = userEvent.setup()
    renderStores()
    await waitFor(() => expect(aisleNames()).toHaveLength(3))

    await user.click(screen.getByText('Biocoop'))

    await waitFor(() => expect(aisleNames()).toEqual(['Vrac']))
    expect(screen.getByRole('textbox', { name: 'Nom du magasin' })).toHaveValue('Biocoop')
  })

  test('renames the store in place and sends only the name', async () => {
    const user = userEvent.setup()
    const update = vi.fn(() => Promise.resolve({ data: { ...STORES[1], name: 'Carrefour Market' } }))
    renderStores(STORES, { update: update as unknown as DataProvider['update'] })
    await waitFor(() => expect(aisleNames()).toHaveLength(3))

    const input = screen.getByRole('textbox', { name: 'Nom du magasin' })
    await user.clear(input)
    await user.type(input, 'Carrefour Market{Enter}')

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update).toHaveBeenCalledWith('stores', expect.objectContaining({ id: '/api/stores/carrefour', data: { name: 'Carrefour Market' } }))
  })

  test('Escape leaves the store name as it was', async () => {
    const user = userEvent.setup()
    const update = vi.fn()
    renderStores(STORES, { update: update as unknown as DataProvider['update'] })
    await waitFor(() => expect(aisleNames()).toHaveLength(3))

    await user.type(screen.getByRole('textbox', { name: 'Nom du magasin' }), ' bis{Escape}')

    expect(screen.getByRole('textbox', { name: 'Nom du magasin' })).toHaveValue('Carrefour')
    expect(update).not.toHaveBeenCalled()
  })

  test('deletes the store only once confirmed', async () => {
    const user = userEvent.setup()
    const deleteOne = vi.fn(() => Promise.resolve({ data: STORES[1] }))
    renderStores(STORES, { delete: deleteOne as unknown as DataProvider['delete'] })
    await waitFor(() => expect(aisleNames()).toHaveLength(3))

    await user.click(screen.getByRole('button', { name: 'Supprimer Carrefour' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Supprimer le magasin « Carrefour » ?')).toBeInTheDocument()
    expect(deleteOne).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(deleteOne).toHaveBeenCalledWith('stores', expect.objectContaining({ id: '/api/stores/carrefour' })))
  })

  test('« + Magasin » creates the store at the end of the tour and opens it', async () => {
    const user = userEvent.setup()
    const create = vi.fn(() => Promise.resolve({ data: { id: '/api/stores/lidl', name: 'Lidl', visitOrder: 2 } }))
    renderStores(STORES, { create: create as unknown as DataProvider['create'] })
    await waitFor(() => expect(aisleNames()).toHaveLength(3))

    await user.click(screen.getByRole('button', { name: '+ Magasin' }))
    await user.type(screen.getByLabelText('Nom'), 'Lidl{Enter}')

    await waitFor(() => expect(create).toHaveBeenCalledWith('stores', { data: { name: 'Lidl', visitOrder: 2 } }))
  })

  test('no store yet: the page still offers « + Magasin »', async () => {
    renderStores([])

    expect(await screen.findByText("Aucun magasin pour l'instant")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '+ Magasin' })).toBeInTheDocument()
    expect(screen.queryByTestId('store-detail')).not.toBeInTheDocument()
  })

  test('the whole page listens on one Mercure stream: a browser has six connections and the shell holds four', async () => {
    const user = userEvent.setup()
    renderStores()
    await waitFor(() => expect(aisleNames()).toHaveLength(3))
    await user.click(screen.getAllByTestId('store-row')[1])

    expect([...streams]).toEqual([JSON.stringify(STORES_PAGE_TOPICS)])
  })
})
