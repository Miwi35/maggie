import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseDarkTheme } from '../../theme'
import { stubAislesApi } from '../../test/aislesApi'
import { ProductList } from './ProductList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const SUPER_U = '01SUPERU'
const BIOCOOP = '01BIOCOOP'

const AISLES = [
  { id: 'u-bread', store: SUPER_U, name: 'Boulangerie', position: 1 },
  { id: 'u-fresh', store: SUPER_U, name: 'Frais', position: 2 },
  { id: 'b-bulk', store: BIOCOOP, name: 'Vrac', position: 1 },
]

const row = (name: string, aisle?: string) => ({
  id: `/api/products/01${name.toUpperCase()}`,
  name,
  category: 'other',
  stockState: 'in_stock',
  aisles: aisle ? [{ store: SUPER_U, aisle }] : [],
})

const PRODUCTS = [row('Beurre', 'u-fresh'), row('Pain', 'u-bread'), row('Sel'), row('Yaourt', 'u-fresh')]

const renderList = () => {
  const getList = vi.fn((resource: string) =>
    Promise.resolve(
      resource === 'stores'
        ? {
            data: [
              { id: `/api/stores/${SUPER_U}`, name: 'Super U' },
              { id: `/api/stores/${BIOCOOP}`, name: 'Biocoop' },
            ],
            total: 2,
          }
        : { data: PRODUCTS, total: PRODUCTS.length },
    ),
  )
  const dataProvider = testDataProvider({
    getList: getList as unknown as DataProvider['getList'],
    getMany: (() => Promise.resolve({ data: [] })) as unknown as DataProvider['getMany'],
  })
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider} theme={veilleuseDarkTheme}>
        <ResourceContextProvider value="products">
          <ProductList />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
  const productReads = () =>
    getList.mock.calls
      .filter(([resource]) => resource === 'products')
      .map((call) => (call as unknown as [string, { filter: object; sort: object }])[1])
  return { productReads }
}

const chooseSuperU = async (user: ReturnType<typeof userEvent.setup>) =>
  user.click(await screen.findByRole('button', { name: 'Super U' }))

describe('ProductList — filtered and sorted by store and aisle (MAG-604)', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'test-jwt')
    stubAislesApi(AISLES)
  })
  afterEach(() => vi.unstubAllGlobals())

  test('lists the products by name until a store is chosen, and the sort pill skips rayon', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    expect(await screen.findByText('Beurre')).toBeInTheDocument()
    expect(productReads()[0].sort).toEqual({ field: 'name', order: 'ASC' })
    expect(screen.queryByText('Rayon')).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Tous', pressed: true })).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Trier : nom' }))
    await waitFor(() => expect(productReads().at(-1)?.sort).toEqual({ field: 'stockState', order: 'ASC' }))
  })

  test('a store pill filters the products, sorts them by aisle and groups them by aisle, « Sans rayon » last', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await chooseSuperU(user)

    await waitFor(() =>
      expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.parentElement?.textContent)).toEqual([
        'Boulangerie1 produit',
        'Frais2 produits',
        'Sans rayon1 produit',
      ]),
    )
    expect(screen.getByRole('button', { name: 'Super U', pressed: true })).toBeInTheDocument()
    const fresh = screen.getByRole('heading', { name: /^Frais/ }).closest('tbody') as HTMLElement
    expect(within(fresh).getByText('Beurre')).toBeInTheDocument()
    expect(within(fresh).getByText('Yaourt')).toBeInTheDocument()

    const last = productReads().at(-1)
    expect(last?.filter).toEqual({ store: SUPER_U })
    expect(last?.sort).toEqual({ field: 'aisle', order: 'ASC' })
  })

  test('the RAYON row offers Tous les rayons, the aisles of the store and « Sans rayon »', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await chooseSuperU(user)
    await screen.findByRole('button', { name: 'Frais' })
    const row = screen.getByText('Rayon').parentElement as HTMLElement
    expect(within(row).getAllByRole('button').map((b) => b.textContent)).toEqual(['Tous les rayons', 'Boulangerie', 'Frais', 'Sans rayon'])
    await user.click(within(row).getByRole('button', { name: 'Sans rayon' }))

    await waitFor(() => expect(productReads().at(-1)?.filter).toEqual({ store: SUPER_U, aisle: 'none' }))
  })

  test('changing the store drops the aisle filter', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await chooseSuperU(user)
    await user.click(await screen.findByRole('button', { name: 'Frais' }))
    await waitFor(() => expect(productReads().at(-1)?.filter).toEqual({ store: SUPER_U, aisle: 'u-fresh' }))

    await user.click(screen.getByRole('button', { name: 'Biocoop' }))

    await waitFor(() => expect(productReads().at(-1)?.filter).toEqual({ store: BIOCOOP }))
  })

  test('the sort pill cycles nom, rayon, stock', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await chooseSuperU(user)
    await user.click(await screen.findByRole('button', { name: 'Trier : rayon' }))
    await waitFor(() => expect(productReads().at(-1)?.sort).toEqual({ field: 'stockState', order: 'ASC' }))

    await user.click(screen.getByRole('button', { name: 'Trier : stock' }))
    await waitFor(() => expect(productReads().at(-1)?.sort).toEqual({ field: 'name', order: 'ASC' }))
    expect(screen.getByRole('button', { name: 'Trier : nom' })).toBeInTheDocument()
  })

  test('the Tous pill goes back to the name order', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await chooseSuperU(user)
    await waitFor(() => expect(productReads().at(-1)?.sort).toEqual({ field: 'aisle', order: 'ASC' }))
    await user.click(screen.getAllByRole('button', { name: 'Tous' })[1])

    await waitFor(() => expect(productReads().at(-1)?.filter).toEqual({}))
    expect(productReads().at(-1)?.sort).toEqual({ field: 'name', order: 'ASC' })
    expect(screen.queryAllByRole('heading', { level: 3 })).toHaveLength(0)
  })
})
