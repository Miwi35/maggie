import { beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Create, Edit, ResourceContextProvider, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { stubAislesApi } from '../../test/aislesApi'
import { ProductForm } from './ProductForm'

const CARREFOUR = '01CARREFOUR'
const BIOCOOP = '01BIOCOOP'
const LECLERC = '01LECLERC'
const stores = [
  { id: `/api/stores/${CARREFOUR}`, name: 'Carrefour' },
  { id: `/api/stores/${BIOCOOP}`, name: 'Biocoop' },
  { id: `/api/stores/${LECLERC}`, name: 'Leclerc' },
]

const AISLES = [
  { id: 'c-fruits', store: CARREFOUR, name: 'Fruits et légumes', position: 1 },
  { id: 'c-dairy', store: CARREFOUR, name: 'Crèmerie', position: 2 },
  { id: 'b-bulk', store: BIOCOOP, name: 'Vrac', position: 1 },
]

const product = {
  id: '/api/products/01LAIT',
  name: 'Lait',
  category: 'dairy',
  preferredStore: `/api/stores/${CARREFOUR}`,
  fallbackStore: `/api/stores/${BIOCOOP}`,
  aisles: [
    { store: CARREFOUR, aisle: 'c-dairy' },
    { store: BIOCOOP, aisle: 'b-bulk' },
  ],
}

const dataProvider = (update = vi.fn().mockResolvedValue({ data: product }), create = vi.fn(), record: Record<string, unknown> = product) =>
  testDataProvider({
    getOne: vi.fn().mockResolvedValue({ data: record }),
    getList: vi.fn().mockResolvedValue({ data: stores, total: stores.length }),
    getMany: vi.fn().mockResolvedValue({ data: [] }),
    update,
    create,
  })

const renderEdit = (record: Record<string, unknown> = product) => {
  const update = vi.fn().mockResolvedValue({ data: product })
  render(
    <MemoryRouter initialEntries={['/products/' + encodeURIComponent(product.id)]}>
      <AdminContext dataProvider={dataProvider(update, vi.fn(), record)}>
        <ResourceContextProvider value="products">
          <Routes>
            <Route
              path="/products/:id"
              element={
                <Edit mutationMode="pessimistic">
                  <ProductForm />
                </Edit>
              }
            />
            <Route path="/products" element={null} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
  return update
}

const save = (user: ReturnType<typeof userEvent.setup>) =>
  user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

describe('ProductForm — the aisle under each store (MAG-493, MAG-604)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt')
  })

  test('shows the aisle right under the usual store and under the fallback store', async () => {
    stubAislesApi(AISLES)
    renderEdit()

    expect(await screen.findByRole('combobox', { name: 'Rayon du magasin habituel' })).toHaveTextContent('Crèmerie')
    expect(await screen.findByRole('combobox', { name: 'Rayon du magasin à défaut' })).toHaveTextContent('Vrac')
    // The separate « Rayons » section is gone.
    expect(screen.queryByText('Rayons')).not.toBeInTheDocument()
  })

  test('offers the aisles of the chosen store only, and saves the one picked keeping the other store', async () => {
    const user = userEvent.setup()
    stubAislesApi(AISLES)
    const update = renderEdit()

    await user.click(await screen.findByRole('combobox', { name: 'Rayon du magasin habituel' }))
    expect(await screen.findByRole('option', { name: 'Fruits et légumes' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Vrac' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'Fruits et légumes' }))
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.aisles).toEqual([
      { store: BIOCOOP, aisle: 'b-bulk' },
      { store: CARREFOUR, aisle: 'c-fruits' },
    ])
  })

  test('« Sans rayon » takes the product out of the store’s aisles', async () => {
    const user = userEvent.setup()
    stubAislesApi(AISLES)
    const update = renderEdit()

    await user.click(await screen.findByRole('combobox', { name: 'Rayon du magasin à défaut' }))
    await user.click(await screen.findByRole('option', { name: 'Sans rayon' }))
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.aisles).toEqual([{ store: CARREFOUR, aisle: 'c-dairy' }])
  })

  test('changing the usual store empties its aisle', async () => {
    const user = userEvent.setup()
    stubAislesApi(AISLES)
    const update = renderEdit()

    await screen.findByRole('combobox', { name: 'Rayon du magasin habituel' })
    const store = screen.getByRole('combobox', { name: 'Magasin habituel' })
    await user.clear(store)
    await user.click(store)
    await user.click(await screen.findByRole('option', { name: 'Leclerc' }))

    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: 'Rayon du magasin habituel' })).not.toHaveTextContent('Crèmerie'),
    )
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    const data = update.mock.calls[0][1].data
    expect(data.preferredStore).toBe(`/api/stores/${LECLERC}`)
    expect(data.aisles).toEqual([{ store: BIOCOOP, aisle: 'b-bulk' }])
  })

  test('no aisle choice while no store is chosen', async () => {
    stubAislesApi(AISLES)
    renderEdit({ ...product, preferredStore: null, fallbackStore: null, aisles: [] })

    await screen.findByDisplayValue('Lait')
    expect(screen.queryByRole('combobox', { name: 'Rayon du magasin habituel' })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Rayon du magasin à défaut' })).not.toBeInTheDocument()
  })

  test('« Nouveau rayon… » creates the aisle in the usual store and puts the product in it', async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi(AISLES)
    const update = renderEdit()

    await user.click(await screen.findByRole('combobox', { name: 'Rayon du magasin habituel' }))
    await user.click(await screen.findByRole('option', { name: 'Nouveau rayon…' }))
    await user.type(screen.getByLabelText('Nom du nouveau rayon'), 'Surgelés')
    await user.click(screen.getByRole('button', { name: 'Créer le rayon' }))

    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: 'Rayon du magasin habituel' })).toHaveTextContent('Surgelés'),
    )
    expect(writes).toEqual([
      expect.objectContaining({ method: 'POST', body: { store: `/api/stores/${CARREFOUR}`, name: 'Surgelés' } }),
    ])
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.aisles).toEqual([
      { store: BIOCOOP, aisle: 'b-bulk' },
      { store: CARREFOUR, aisle: 'new-1' },
    ])
  })

  test('« Nouveau rayon… » under « À défaut » creates it in the fallback store', async () => {
    const user = userEvent.setup()
    const { writes } = stubAislesApi(AISLES)
    renderEdit()

    await user.click(await screen.findByRole('combobox', { name: 'Rayon du magasin à défaut' }))
    await user.click(await screen.findByRole('option', { name: 'Nouveau rayon…' }))
    await user.type(screen.getByLabelText('Nom du nouveau rayon'), 'Épicerie')
    await user.click(screen.getByRole('button', { name: 'Créer le rayon' }))

    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: 'Rayon du magasin à défaut' })).toHaveTextContent('Épicerie'),
    )
    expect(writes).toEqual([
      expect.objectContaining({ method: 'POST', body: { store: `/api/stores/${BIOCOOP}`, name: 'Épicerie' } }),
    ])
  })

  test('« + Rayon dans un autre magasin » sets the aisle in a third store', async () => {
    const user = userEvent.setup()
    stubAislesApi([...AISLES, { id: 'l-bread', store: LECLERC, name: 'Boulangerie', position: 1 }])
    const update = renderEdit()

    await user.click(await screen.findByRole('button', { name: '+ Rayon dans un autre magasin' }))
    await user.click(await screen.findByRole('combobox', { name: 'Autre magasin' }))
    await user.click(await screen.findByRole('option', { name: 'Leclerc' }))
    await user.click(await screen.findByRole('combobox', { name: 'Leclerc' }))
    await user.click(await screen.findByRole('option', { name: 'Boulangerie' }))
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.aisles).toEqual(
      expect.arrayContaining([{ store: LECLERC, aisle: 'l-bread' }]),
    )
  })

  test('a new product has no aisle choice: the API sets aisles on an update only', async () => {
    const { fetchMock } = stubAislesApi(AISLES)
    render(
      <MemoryRouter initialEntries={['/products/create']}>
        <AdminContext dataProvider={dataProvider()}>
          <ResourceContextProvider value="products">
            <Routes>
              <Route
                path="/products/create"
                element={
                  <Create>
                    <ProductForm />
                  </Create>
                }
              />
            </Routes>
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    await screen.findByLabelText(/Nom/)
    expect(screen.getByRole('combobox', { name: 'Magasin habituel' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: /Rayon/ })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/aisles'))).toEqual([])
  })
})
