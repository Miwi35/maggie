import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { veilleuseDarkTheme } from '../../theme'
import { stubAislesApi } from '../../test/aislesApi'
import { ProductList } from './ProductList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const SUPER_U = '01SUPERU'
const BIOCOOP = '01BIOCOOP'
const STORES = [
  { id: `/api/stores/${SUPER_U}`, name: 'Super U' },
  { id: `/api/stores/${BIOCOOP}`, name: 'Biocoop' },
]
const AISLES = [
  { id: 'u-bread', store: SUPER_U, name: 'Boulangerie', position: 1 },
  { id: 'u-fresh', store: SUPER_U, name: 'Frais', position: 2 },
]

const MONTH = new Date().getMonth() + 1
const OTHER_MONTH = (MONTH % 12) + 1

const product = (name: string, extra: Record<string, unknown> = {}) => ({
  id: `/api/products/01${name.toUpperCase()}`,
  name,
  category: 'other',
  stockState: 'in_stock',
  aisles: [],
  ...extra,
})

const PRODUCTS = [
  product('Fraises', {
    category: 'produce',
    seasonMonths: [MONTH],
    preferredStore: STORES[0].id,
    fallbackStore: STORES[1].id,
    aisles: [{ store: SUPER_U, aisle: 'u-fresh' }],
  }),
  product('Courgettes', { category: 'produce', seasonMonths: [OTHER_MONTH] }),
  product('Beurre', {
    category: 'dairy',
    stockState: 'low',
    packagingUnit: 'pack',
    packagingSize: 250,
    packagingSizeUnit: 'g',
    aisles: [{ store: SUPER_U, aisle: 'u-fresh' }],
  }),
  product('Riz', { category: 'grain', packagingUnit: 'pack', packagingSize: 1, packagingSizeUnit: 'kg' }),
]

const renderPage = (path = '/products') => {
  const dataProvider = testDataProvider({
    getList: vi.fn((resource: string) =>
      Promise.resolve(
        resource === 'stores'
          ? { data: STORES, total: STORES.length }
          : { data: PRODUCTS, total: PRODUCTS.length },
      ),
    ) as unknown as DataProvider['getList'],
    getMany: vi.fn((_resource: string, { ids }: { ids: string[] }) =>
      Promise.resolve({ data: STORES.filter((s) => ids.includes(s.id)) }),
    ) as unknown as DataProvider['getMany'],
    getOne: vi.fn((_resource: string, { id }: { id: string }) =>
      Promise.resolve({ data: PRODUCTS.find((p) => p.id === id) ?? product('Inconnu') }),
    ) as unknown as DataProvider['getOne'],
    delete: vi.fn().mockResolvedValue({ data: PRODUCTS[2] }),
  })
  render(
    <MemoryRouter initialEntries={[path]}>
      <AdminContext dataProvider={dataProvider} theme={veilleuseDarkTheme}>
        <ResourceContextProvider value="products">
          <Routes>
            <Route path="/products" element={<ProductList />} />
            <Route path="/products/create" element={<ProductList />} />
            <Route path="/products/:id" element={<ProductList />} />
          </Routes>
        </ResourceContextProvider>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )
  return dataProvider
}

const names = () => screen.queryAllByTestId('product-name').map((n) => n.textContent)

describe('ProductList — the mockup « Admin-Produits » (MAG-622)', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'test-jwt')
    stubAislesApi(AISLES)
  })
  afterEach(() => vi.unstubAllGlobals())

  test('reads Produit · Rayon (habituel) · Magasin · à défaut · Conditionnement · Stock without a store, Rayon · Super U with one', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Fraises')
    const headers = () => screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers()).toEqual(['Produit', 'Rayon (habituel)', 'Magasin · à défaut', 'Conditionnement', 'Stock'])
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /éditer|edit/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /supprimer|delete/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /exporter|export/i })).not.toBeInTheDocument()

    const row = screen.getByText('Fraises').closest('tr') as HTMLElement
    expect(within(row).getByText('Super U · Biocoop')).toBeInTheDocument()
    const butter = screen.getByText('Beurre').closest('tr') as HTMLElement
    expect(within(butter).getByText('paquet de 250 g')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Super U' }))
    await waitFor(() => expect(headers()).toEqual(['Produit', 'Rayon · Super U', 'Magasin · à défaut', 'Conditionnement', 'Stock']))
  })

  test('without a store the list is flat and names the usual aisle, « Sans rayon » otherwise', async () => {
    renderPage()

    await screen.findByText('Fraises')
    expect(screen.queryAllByRole('heading', { level: 3 })).toHaveLength(0)
    await waitFor(() => expect(within(screen.getByText('Fraises').closest('tr') as HTMLElement).getByText('Frais')).toBeInTheDocument())
    expect(within(screen.getByText('Riz').closest('tr') as HTMLElement).getByText('Sans rayon')).toBeInTheDocument()
    for (const english of ['produce', 'dairy', 'grain', 'other']) {
      expect(screen.queryByText(english)).not.toBeInTheDocument()
    }
  })

  test('offers the pastilles Tous · De saison · Conditionnés · Stock bas, Tous being on', async () => {
    renderPage()

    await screen.findByText('Fraises')
    const pastilles = screen.getAllByRole('button', { pressed: true }).concat(screen.getAllByRole('button', { pressed: false }))
    expect(pastilles.map((p) => p.textContent)).toEqual(expect.arrayContaining(['Conditionnés', 'De saison', 'Stock bas', 'Tous']))
    expect(screen.getAllByRole('button', { name: 'Tous' })[0]).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Magasin')).toBeInTheDocument()
  })

  test('« De saison » keeps the products in season this month', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Fraises')
    await user.click(screen.getByRole('button', { name: 'De saison' }))

    expect(screen.getByRole('button', { name: 'De saison' })).toHaveAttribute('aria-pressed', 'true')
    expect(names()).toEqual(['Fraises'])
  })

  test('« Conditionnés » keeps the products sold in a packaging', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Fraises')
    await user.click(screen.getByRole('button', { name: 'Conditionnés' }))

    expect(names()).toEqual(['Beurre', 'Riz'])
  })

  test('« Stock bas » keeps the products that are running low or out, and « Tous » brings the rest back', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Fraises')
    await user.click(screen.getByRole('button', { name: 'Stock bas' }))
    expect(names()).toEqual(['Beurre'])

    await user.click(screen.getAllByRole('button', { name: 'Tous' })[0])
    expect(names()).toHaveLength(4)
  })

  test('the search narrows by name, ignoring case and accents', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Fraises')
    await user.type(screen.getByRole('searchbox', { name: 'Rechercher un produit' }), 'BEUR')

    expect(names()).toEqual(['Beurre'])
    await user.clear(screen.getByRole('searchbox', { name: 'Rechercher un produit' }))
    await user.type(screen.getByRole('searchbox', { name: 'Rechercher un produit' }), 'zzz')
    expect(await screen.findByText('Aucun produit ne correspond.')).toBeInTheDocument()
  })

  test('a store groups by its aisles, each head reading its name and « n produits »', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Super U' }))

    await waitFor(() =>
      expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.parentElement?.textContent)).toEqual(['Frais2 produits', 'Sans rayon2 produits']),
    )
    const row = screen.getByText('Beurre').closest('tr') as HTMLElement
    expect(within(row).getByText('Frais')).toBeInTheDocument()
  })

  test('the stock reads en stock, bas, épuisé', async () => {
    renderPage()

    await screen.findByText('Beurre')
    expect(within(screen.getByText('Beurre').closest('tr') as HTMLElement).getByText('bas')).toBeInTheDocument()
    expect(within(screen.getByText('Riz').closest('tr') as HTMLElement).getByText('en stock')).toBeInTheDocument()
    expect(screen.queryByText('Stock faible')).not.toBeInTheDocument()
  })

  test('the product whose fiche is open is the selected row', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Beurre'))
    await screen.findByRole('dialog', { name: 'Beurre' })

    const rowOf = (name: string) => screen.getAllByTestId('product-name').find((n) => n.textContent === name)?.closest('tr')
    expect(rowOf('Beurre')).toHaveAttribute('aria-selected', 'true')
    expect(rowOf('Riz')).toHaveAttribute('aria-selected', 'false')
  })

  test('a click on a row opens the product in a panel on the right, over the list', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Beurre'))

    const panel = await screen.findByRole('dialog', { name: 'Beurre' })
    expect(await within(panel).findByRole('textbox', { name: /Nom/ })).toHaveValue('Beurre')
    expect(screen.getByText('Fraises')).toBeInTheDocument()

    await user.click(within(panel).getByRole('button', { name: 'Fermer' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByText('Fraises')).toBeInTheDocument()
  })

  test('opening a product by its address shows the same panel', async () => {
    renderPage(`/products/${encodeURIComponent(PRODUCTS[2].id)}`)

    expect(await screen.findByRole('dialog', { name: 'Beurre' })).toBeInTheDocument()
    expect(screen.getByText('Courgettes')).toBeInTheDocument()
  })

  test('« + Produit » opens an empty panel', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '+ Produit' }))

    const panel = await screen.findByRole('dialog', { name: 'Nouveau produit' })
    expect(within(panel).getByRole('textbox', { name: /Nom/ })).toHaveValue('')
  })

  test('deleting is in the panel and asks for a confirmation first', async () => {
    const user = userEvent.setup()
    const dataProvider = renderPage()

    await user.click(await screen.findByText('Beurre'))
    const panel = await screen.findByRole('dialog', { name: 'Beurre' })
    await user.click(await within(panel).findByRole('button', { name: 'Supprimer' }))

    const confirm = await screen.findByRole('dialog', { name: 'Supprimer ce produit ?' })
    expect(dataProvider.delete).not.toHaveBeenCalled()
    await user.click(within(confirm).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(dataProvider.delete).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})
