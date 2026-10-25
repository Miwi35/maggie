import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
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
      <AdminContext dataProvider={dataProvider}>
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

  test('reads Produit · Rayon · Magasin · À défaut · Conditionnement · Stock, with no checkbox and no row buttons', async () => {
    renderPage()

    await screen.findByText('Fraises')
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers.slice(0, 6)).toEqual(['Produit', 'Rayon', 'Magasin', 'À défaut', 'Conditionnement', 'Stock'])
    expect(screen.queryByText('Catégorie')).not.toBeInTheDocument()
    expect(screen.queryByText('Conservation (jours)')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /éditer|edit/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /supprimer|delete/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /exporter|export/i })).not.toBeInTheDocument()

    const row = screen.getByText('Fraises').closest('tr') as HTMLElement
    expect(within(row).getByText('Super U')).toBeInTheDocument()
    expect(within(row).getByText('Biocoop')).toBeInTheDocument()
    const butter = screen.getByText('Beurre').closest('tr') as HTMLElement
    expect(within(butter).getByText('paquet de 250 g')).toBeInTheDocument()
    expect(within(butter).getByText('Stock faible')).toBeInTheDocument()
  })

  test('groups the products by rayon, named in French — never the stored English category', async () => {
    renderPage()

    await screen.findByText('Fraises')
    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(headings).toEqual(['Fruits & Légumes · 2', 'Produits laitiers · 1', 'Céréales · 1'])
    for (const english of ['produce', 'dairy', 'grain', 'other']) {
      expect(screen.queryByText(english)).not.toBeInTheDocument()
    }
  })

  test('offers the pastilles Tous · De saison · Conditionnés · Stock bas, Tous being on', async () => {
    renderPage()

    await screen.findByText('Fraises')
    const pastilles = screen.getAllByRole('button', { pressed: true }).concat(screen.getAllByRole('button', { pressed: false }))
    expect(pastilles.map((p) => p.textContent).sort()).toEqual(['Conditionnés', 'De saison', 'Stock bas', 'Tous'].sort())
    expect(screen.getByRole('button', { name: 'Tous' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('« De saison » keeps the products in season this month, still grouped by rayon', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Fraises')
    await user.click(screen.getByRole('button', { name: 'De saison' }))

    expect(screen.getByRole('button', { name: 'De saison' })).toHaveAttribute('aria-pressed', 'true')
    expect(names()).toEqual(['Fraises'])
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Fruits & Légumes · 1'])
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

    await user.click(screen.getByRole('button', { name: 'Tous' }))
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

  test('a store groups by its aisles, and the Rayon column names the aisle', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('combobox', { name: 'Magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Super U' }))

    await waitFor(() =>
      expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Frais · 2', 'Sans rayon · 2']),
    )
    const row = screen.getByText('Beurre').closest('tr') as HTMLElement
    expect(within(row).getAllByText('Frais').length).toBeGreaterThan(0)
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
