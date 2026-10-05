import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Edit, ResourceContextProvider, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ProductForm } from './ProductForm'

const halles = { id: '/api/stores/01M3VA6TKHF1CB6GMPSRV8WE7P', name: 'Halles du voisin' }
const primeur = { id: '/api/stores/01M3VA6TKHF1CB6GMPSRV8WE7Q', name: 'Primeur' }

const product = {
  id: '/api/products/01M3VA6TKHF1CB6GMPSRV8WE7R',
  name: 'Câpres',
  category: 'other',
  preferredStore: halles.id,
  fallbackStore: primeur.id,
  shelfLifeDays: 30,
}

const renderEdit = (update = vi.fn().mockResolvedValue({ data: product })) => {
  const stores = [halles, primeur]
  render(
    <MemoryRouter initialEntries={['/products/' + encodeURIComponent(product.id)]}>
      <AdminContext
        dataProvider={testDataProvider({
          getOne: vi.fn().mockResolvedValue({ data: product }),
          getList: vi.fn().mockResolvedValue({ data: stores, total: stores.length }),
          getMany: vi.fn().mockImplementation((_resource: string, { ids }: { ids: string[] }) =>
            Promise.resolve({ data: stores.filter((s) => ids.includes(s.id)) }),
          ),
          update,
        })}
      >
        <ResourceContextProvider value="products">
          <Routes>
            <Route path="/products/:id" element={
                <Edit mutationMode="pessimistic">
                  <ProductForm />
                </Edit>
              } />
            <Route path="/products" element={null} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
  return update
}

describe('ProductForm', () => {
  test('shows the preferred store the product already has', async () => {
    renderEdit()

    expect(await screen.findByDisplayValue('Halles du voisin')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Primeur')).toBeInTheDocument()
  })

  test('saves the stores and the shelf life it was given, untouched', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    await screen.findByDisplayValue('Halles du voisin')
    await user.type(screen.getByLabelText(/Nom/), ' au sel')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const data = update.mock.calls[0][1].data
    expect(data.preferredStore).toBe(halles.id)
    expect(data.fallbackStore).toBe(primeur.id)
    expect(data.shelfLifeDays).toBe(30)
  })

  test('saves an empty preferred store as null so the API clears it', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    const input = await screen.findByDisplayValue('Halles du voisin')
    await user.clear(input)
    await user.tab()
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const data = update.mock.calls[0][1].data
    expect(data.preferredStore).toBeNull()
    expect(data.fallbackStore).toBe(primeur.id)
  })
})
