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

const renderEdit = (update = vi.fn().mockResolvedValue({ data: product }), record: Record<string, unknown> = product) => {
  const stores = [halles, primeur]
  render(
    <MemoryRouter initialEntries={['/products/' + encodeURIComponent(product.id)]}>
      <AdminContext
        dataProvider={testDataProvider({
          getOne: vi.fn().mockResolvedValue({ data: record }),
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

  test('shows the packaging the product already has, as a sentence', async () => {
    renderEdit(undefined, { ...product, packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g' })

    expect(await screen.findByText("On l'achète en : paquet de 500 g")).toBeInTheDocument()
  })

  test('says the recipe unit is kept when the product has no packaging', async () => {
    renderEdit()

    expect(await screen.findByText(/Pas de conditionnement/)).toBeInTheDocument()
  })

  test('saves the packaging typed in, and the preview follows the input', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    await screen.findByDisplayValue('Halles du voisin')
    await user.click(screen.getByLabelText('Conditionnement'))
    await user.click(await screen.findByRole('option', { name: 'paquet' }))
    await user.type(screen.getByLabelText('Contenu'), '500')
    await user.click(screen.getByLabelText('Unité du contenu'))
    await user.click(await screen.findByRole('option', { name: 'g' }))

    expect(screen.getByTestId('packaging-preview')).toHaveTextContent("On l'achète en : paquet de 500 g")

    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const data = update.mock.calls[0][1].data
    expect(data.packagingUnit).toBe('pack')
    expect(data.packagingSize).toBe(500)
    expect(data.packagingSizeUnit).toBe('g')
  })

  test('refuses a content without its unit, as the API does', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    await screen.findByDisplayValue('Halles du voisin')
    await user.click(screen.getByLabelText('Conditionnement'))
    await user.click(await screen.findByRole('option', { name: 'paquet' }))
    await user.type(screen.getByLabelText('Contenu'), '500')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    expect((await screen.findAllByText('La quantité et son unité vont ensemble.')).length).toBeGreaterThan(0)
    expect(update).not.toHaveBeenCalled()
  })

  test('announces a product with no stock state as in stock', async () => {
    renderEdit()

    await screen.findByDisplayValue('Halles du voisin')
    expect(screen.getByText("Ce qu'il m'en reste")).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'État du stock' })).toHaveTextContent('En stock')
  })

  test('saves the stock state, the restock quantity and the automatic restock typed in', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    await screen.findByDisplayValue('Halles du voisin')
    await user.type(screen.getByLabelText('Quantité de réapprovisionnement'), '2')
    await user.click(screen.getByLabelText('Réapprovisionnement automatique'))
    await user.click(screen.getByLabelText(/État du stock/))
    await user.click(await screen.findByRole('option', { name: 'Rupture' }))
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const data = update.mock.calls[0][1].data
    expect(data.stockState).toBe('out')
    expect(data.restockQuantity).toBe(2)
    expect(data.autoRestock).toBe(true)
  })

  test('shows the stock the product already has', async () => {
    renderEdit(undefined, { ...product, stockState: 'low', restockQuantity: 3, autoRestock: true })

    expect(await screen.findByDisplayValue('3')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'État du stock' })).toHaveTextContent('Stock faible')
    expect(screen.getByLabelText('Réapprovisionnement automatique')).toBeChecked()
  })

  test('saves an emptied restock quantity as null so the API clears it', async () => {
    const user = userEvent.setup()
    const update = renderEdit(undefined, { ...product, restockQuantity: 3 })

    await user.clear(await screen.findByDisplayValue('3'))
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.restockQuantity).toBeNull()
  })

  test('refuses a negative restock quantity', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    await screen.findByDisplayValue('Halles du voisin')
    await user.type(screen.getByLabelText('Quantité de réapprovisionnement'), '-1')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(screen.getByLabelText('Quantité de réapprovisionnement')).toBeInvalid())
    expect(update).not.toHaveBeenCalled()
  })
})
