import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { RecipeCreate } from './RecipeCreate'

vi.mock('./CiqualFoodAutocomplete', () => ({ CiqualFoodAutocomplete: () => null }))

const renderCreate = (create = vi.fn().mockResolvedValue({ data: { id: '/api/recipes/1' } })) => {
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider({ create })}>
        <ResourceContextProvider value="recipes">
          <RecipeCreate />
        </ResourceContextProvider>
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
})
