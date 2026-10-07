import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { IngredientCreate } from './IngredientCreate'
import { IngredientEdit } from './IngredientEdit'

// Recette MAG-292: what a product is bought in belongs to Courses. The cookbook
// shows no packaging; an ingredient's packaging is set from its product form.
const rice = {
  id: '/api/ingredients/01M3VA6TKHF1CB6GMPSRV8WE7R',
  name: 'Riz',
  category: 'grain',
  packagingUnit: 'pack',
  packagingSize: 500,
  packagingSizeUnit: 'g',
}

const renderEdit = (update = vi.fn().mockResolvedValue({ data: rice })) => {
  render(
    <MemoryRouter initialEntries={['/ingredients/' + encodeURIComponent(rice.id)]}>
      <AdminContext dataProvider={testDataProvider({ getOne: vi.fn().mockResolvedValue({ data: rice }), update })}>
        <ResourceContextProvider value="ingredients">
          <Routes>
            <Route path="/ingredients/:id" element={<IngredientEdit />} />
            <Route path="/ingredients" element={null} />
          </Routes>
        </ResourceContextProvider>
        {/* The edit is undoable: the update only leaves once the notification closes. */}
        <Notification autoHideDuration={100} />
      </AdminContext>
    </MemoryRouter>,
  )
  return update
}

describe('Ingredient forms', { timeout: 30_000 }, () => {
  beforeEach(() =>
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: () => Promise.resolve(null) })),
  )
  afterEach(() => vi.unstubAllGlobals())

  test('the edit form of an ingredient shows no packaging, even when it has one', async () => {
    renderEdit()

    expect(await screen.findByDisplayValue('Riz')).toBeInTheDocument()
    expect(screen.queryByText("Comment on l'achète")).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Conditionnement')).not.toBeInTheDocument()
    expect(screen.queryByText(/paquet de 500 g/)).not.toBeInTheDocument()
  })

  test('saving an ingredient keeps the packaging set from Courses', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    await user.type(await screen.findByDisplayValue('Riz'), ' basmati')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const data = update.mock.calls[0][1].data
    expect(data.name).toBe('Riz basmati')
    expect(data.packagingUnit).toBe('pack')
    expect(data.packagingSize).toBe(500)
    expect(data.packagingSizeUnit).toBe('g')
  })

  test('the create form of an ingredient shows no packaging', () => {
    render(
      <MemoryRouter>
        <AdminContext dataProvider={testDataProvider()}>
          <ResourceContextProvider value="ingredients">
            <IngredientCreate />
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    expect(screen.getByLabelText(/Nom/)).toBeInTheDocument()
    expect(screen.queryByText("Comment on l'achète")).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Conditionnement')).not.toBeInTheDocument()
  })
})
