import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { RecipeEdit } from './RecipeEdit'

/**
 * MAG-255: the quantity of an ingredient already in a recipe could not be changed.
 *
 * The form is given the recipe as the API serves it — each line with its
 * ingredient embedded and the Ciqual code of that ingredient — and has to send
 * the line back with the new quantity, still carrying the code.
 */

const PASTA = {
  alim_code: '9810',
  alim_name_fr: 'Pâtes alimentaires, cuites',
  alim_group_name_fr: 'produits céréaliers',
  kcal_per100g: 130,
  protein_per100g: 4.5,
  carbs_per100g: 25,
  fat_per100g: 0.9,
}

const recipe = {
  id: '01R',
  name: 'Pâtes à la tomate',
  servings: 4,
  tags: ['pasta'],
  notes: null,
  ingredients: [
    {
      id: '01L1',
      ingredient: { '@id': '/api/ingredients/01I1', id: '01I1', name: 'Pâtes', ciqualAlimCode: '9810' },
      ingredientName: 'Pâtes',
      ciqualAlimCode: '9810',
      quantity: 200,
      unit: 'g',
    },
  ],
}

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const renderEdit = (update: ReturnType<typeof vi.fn>) => {
  render(
    <MemoryRouter initialEntries={['/recipes/01R']}>
      <AdminContext
        dataProvider={testDataProvider({ getOne: vi.fn().mockResolvedValue({ data: recipe }), update })}
        i18nProvider={i18nProvider}
      >
        <Routes>
          <Route
            path="/recipes/:id"
            element={
              <ResourceContextProvider value="recipes">
                <RecipeEdit />
              </ResourceContextProvider>
            }
          />
        </Routes>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )
}

describe('RecipeEdit', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve(PASTA) } as Response)),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('sends back the line with its new quantity and its Ciqual food', async () => {
    const user = userEvent.setup()
    const update = vi.fn().mockResolvedValue({ data: recipe })
    renderEdit(update)

    const quantity = await screen.findByLabelText(/Quantité/)
    expect(quantity).toHaveValue(200)
    await user.clear(quantity)
    await user.type(quantity, '300')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const line = update.mock.calls[0][1].data.ingredients[0]
    expect(line).toMatchObject({ ciqualAlimCode: '9810', quantity: 300, unit: 'g' })
  })

  test('shows the food of an existing line', async () => {
    renderEdit(vi.fn())

    expect(await screen.findByDisplayValue(PASTA.alim_name_fr)).toBeInTheDocument()
  })

  test('refuses a quantity of zero without calling the API', async () => {
    const user = userEvent.setup()
    const update = vi.fn()
    renderEdit(update)

    const quantity = await screen.findByLabelText(/Quantité/)
    await user.clear(quantity)
    await user.type(quantity, '0')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('La quantité doit être supérieure à 0')).toBeInTheDocument()
    expect(update).not.toHaveBeenCalled()
  })

  test('keeps the form and says so when the API refuses the recipe', async () => {
    const user = userEvent.setup()
    const update = vi.fn().mockRejectedValue(new Error('Each ingredient quantity must be a number greater than 0.'))
    renderEdit(update)

    const quantity = await screen.findByLabelText(/Quantité/)
    await user.clear(quantity)
    await user.type(quantity, '300')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText(/greater than 0/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Quantité/)).toHaveValue(300)
  })
})
