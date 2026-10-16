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
  aisles: [
    { store: CARREFOUR, aisle: 'c-dairy' },
    { store: BIOCOOP, aisle: 'b-bulk' },
  ],
}

const dataProvider = (update = vi.fn().mockResolvedValue({ data: product }), create = vi.fn()) =>
  testDataProvider({
    getOne: vi.fn().mockResolvedValue({ data: product }),
    getList: vi.fn().mockResolvedValue({ data: stores, total: stores.length }),
    getMany: vi.fn().mockResolvedValue({ data: [] }),
    update,
    create,
  })

const renderEdit = () => {
  const update = vi.fn().mockResolvedValue({ data: product })
  render(
    <MemoryRouter initialEntries={['/products/' + encodeURIComponent(product.id)]}>
      <AdminContext dataProvider={dataProvider(update)}>
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

describe('ProductForm — the aisle of the product in each store (MAG-493)', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt')
  })

  test('shows the aisle the product has in each store that has aisles', async () => {
    stubAislesApi(AISLES)
    renderEdit()

    expect(await screen.findByRole('combobox', { name: 'Carrefour' })).toHaveTextContent('Crèmerie')
    expect(screen.getByRole('combobox', { name: 'Biocoop' })).toHaveTextContent('Vrac')
    // Leclerc has no aisle: nothing to choose there.
    expect(screen.queryByRole('combobox', { name: 'Leclerc' })).not.toBeInTheDocument()
  })

  test('saves the aisle chosen in one store and keeps the others', async () => {
    const user = userEvent.setup()
    stubAislesApi(AISLES)
    const update = renderEdit()

    await user.click(await screen.findByRole('combobox', { name: 'Carrefour' }))
    await user.click(await screen.findByRole('option', { name: 'Fruits et légumes' }))
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

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

    await user.click(await screen.findByRole('combobox', { name: 'Biocoop' }))
    await user.click(await screen.findByRole('option', { name: 'Sans rayon' }))
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.aisles).toEqual([{ store: CARREFOUR, aisle: 'c-dairy' }])
  })

  test('no aisle choice when no store has aisles', async () => {
    stubAislesApi([])
    renderEdit()

    await screen.findByDisplayValue('Lait')
    await waitFor(() => expect(screen.queryByText('Rayons')).not.toBeInTheDocument())
    expect(screen.queryByRole('combobox', { name: 'Carrefour' })).not.toBeInTheDocument()
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
    expect(screen.queryByText('Rayons')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/aisles'))).toEqual([])
  })
})
