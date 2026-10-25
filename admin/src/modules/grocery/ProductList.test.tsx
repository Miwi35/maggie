import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
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
      <AdminContext dataProvider={dataProvider}>
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

describe('ProductList — filtered and sorted by store and aisle (MAG-604)', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'test-jwt')
    stubAislesApi(AISLES)
  })
  afterEach(() => vi.unstubAllGlobals())

  test('lists the products by name until a store is chosen', async () => {
    const { productReads } = renderList()

    expect(await screen.findByText('Beurre')).toBeInTheDocument()
    expect(productReads()[0].sort).toEqual({ field: 'name', order: 'ASC' })
    expect(screen.queryByRole('combobox', { name: 'Rayon' })).not.toBeInTheDocument()
    // No store, no way through a store: sorting by aisle waits for one.
    await userEvent.setup().click(screen.getByRole('combobox', { name: 'Trier par' }))
    expect(await screen.findByRole('option', { name: 'Rayon' })).toHaveAttribute('aria-disabled', 'true')
  })

  test('a store filters the products, sorts them by aisle and groups them by aisle, « Sans rayon » last', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await user.click(await screen.findByRole('combobox', { name: 'Magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Super U' }))

    await waitFor(() =>
      expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Boulangerie · 1', 'Frais · 2', 'Sans rayon · 1']),
    )
    const fresh = screen.getByRole('heading', { name: 'Frais · 2' }).closest('tbody') as HTMLElement
    expect(within(fresh).getByText('Beurre')).toBeInTheDocument()
    expect(within(fresh).getByText('Yaourt')).toBeInTheDocument()

    const last = productReads().at(-1)
    expect(last?.filter).toEqual({ store: SUPER_U })
    expect(last?.sort).toEqual({ field: 'aisle', order: 'ASC' })
  })

  test('the aisle filter offers the aisles of the store and « Sans rayon »', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await user.click(await screen.findByRole('combobox', { name: 'Magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Super U' }))
    await user.click(await screen.findByRole('combobox', { name: 'Rayon' }))
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual([
      'Tous les rayons',
      'Boulangerie',
      'Frais',
      'Sans rayon',
    ])
    await user.click(screen.getByRole('option', { name: 'Sans rayon' }))

    await waitFor(() => expect(productReads().at(-1)?.filter).toEqual({ store: SUPER_U, aisle: 'none' }))
  })

  test('changing the store drops the aisle filter', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await user.click(await screen.findByRole('combobox', { name: 'Magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Super U' }))
    await user.click(await screen.findByRole('combobox', { name: 'Rayon' }))
    await user.click(await screen.findByRole('option', { name: 'Frais' }))
    await waitFor(() => expect(productReads().at(-1)?.filter).toEqual({ store: SUPER_U, aisle: 'u-fresh' }))

    await user.click(screen.getByRole('combobox', { name: 'Magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Biocoop' }))

    await waitFor(() => expect(productReads().at(-1)?.filter).toEqual({ store: BIOCOOP }))
  })

  test('sorts by name, aisle or stock', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await user.click(await screen.findByRole('combobox', { name: 'Magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Super U' }))
    await user.click(screen.getByRole('combobox', { name: 'Trier par' }))
    await user.click(await screen.findByRole('option', { name: 'Stock' }))
    await waitFor(() => expect(productReads().at(-1)?.sort).toEqual({ field: 'stockState', order: 'ASC' }))

    await user.click(screen.getByRole('combobox', { name: 'Trier par' }))
    await user.click(await screen.findByRole('option', { name: 'Nom' }))
    await waitFor(() => expect(productReads().at(-1)?.sort).toEqual({ field: 'name', order: 'ASC' }))
  })

  test('clearing the store goes back to the name order', async () => {
    const user = userEvent.setup()
    const { productReads } = renderList()

    await user.click(await screen.findByRole('combobox', { name: 'Magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Super U' }))
    await user.click(screen.getByRole('combobox', { name: 'Magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Tous les magasins' }))

    await waitFor(() => expect(productReads().at(-1)?.filter).toEqual({}))
    expect(productReads().at(-1)?.sort).toEqual({ field: 'name', order: 'ASC' })
    expect((await screen.findAllByRole('heading', { level: 3 })).map((h) => h.textContent)).toEqual(['Autre · 4'])
  })
})
