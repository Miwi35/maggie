import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, ResourceDefinitionContextProvider, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { RecipeCreate } from './RecipeCreate'

vi.mock('./CiqualFoodAutocomplete', () => ({ CiqualFoodAutocomplete: () => null }))

// The API Platform guesser gives `recipes` an edit page, which is where react-admin sends a saved record by default.
const definitions = { recipes: { name: 'recipes', hasList: true, hasEdit: true } }

const renderCreate = (create = vi.fn().mockResolvedValue({ data: { id: '/api/recipes/1' } })) => {
  render(
    <MemoryRouter initialEntries={['/recipes/create']}>
      <AdminContext dataProvider={testDataProvider({ create })}>
        <Notification />
        <ResourceDefinitionContextProvider definitions={definitions}>
          <ResourceContextProvider value="recipes">
            <Routes>
              <Route path="/recipes/create" element={<RecipeCreate />} />
              <Route path="/recipes" element={<p>Liste des recettes</p>} />
              <Route path="/recipes/:id" element={<p>Formulaire de la recette</p>} />
            </Routes>
          </ResourceContextProvider>
        </ResourceDefinitionContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
  return create
}

describe('RecipeCreate', () => {
  test('sends the tags as a list of strings, not as the typed text', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(screen.getByLabelText(/Nom/), 'Pâtes')
    await user.type(screen.getByLabelText(/Tags/), 'rapide, végétarien')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data.tags).toEqual(['rapide', 'végétarien'])
  })

  test('sends an empty list when no tag is typed', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(screen.getByLabelText(/Nom/), 'Pâtes')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data.tags ?? []).toEqual([])
  })

  test('goes back to the list once the recipe is saved', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.type(screen.getByLabelText(/Nom/), 'Pâtes')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    expect(await screen.findByText('Liste des recettes')).toBeInTheDocument()
  })

  test('says a recipe was saved, not a generic element', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.type(screen.getByLabelText(/Nom/), 'Pâtes')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    expect(await screen.findByText('Recette enregistrée')).toBeInTheDocument()
  })
})
