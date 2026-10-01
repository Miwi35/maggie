import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { RecipeCreate } from './RecipeCreate'
import { IngredientCreate } from './IngredientCreate'

const TOMATO = {
  alim_code: '20047',
  alim_name_fr: 'Tomate, pulpe, appertisée',
  alim_group_name_fr: 'fruits, légumes, légumineuses et oléagineux',
  kcal_per100g: 23.5,
  protein_per100g: 1.1,
  carbs_per100g: 3.4,
  fat_per100g: 0.2,
}

const stubCiqual = () =>
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/ciqual/foods?')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([TOMATO]) } as Response)
      }
      if (url.includes(`/ciqual/foods/${TOMATO.alim_code}`)) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve(TOMATO) } as Response)
      }
      return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) } as unknown as Response)
    }),
  )

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const renderForm = (resource: string, element: React.ReactElement) => {
  const create = vi.fn().mockResolvedValue({ data: { id: `/api/${resource}/1` } })
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider({ create })} i18nProvider={i18nProvider}>
        <ResourceContextProvider value={resource}>{element}</ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
  return create
}

const pickTomato = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.type(screen.getByLabelText(/Aliment Ciqual/), 'tomate')
  await user.click(await screen.findByRole('option', { name: new RegExp(TOMATO.alim_name_fr, 'i') }, { timeout: 5_000 }))
}

describe('CiqualFoodAutocomplete', { timeout: 30_000 }, () => {
  beforeEach(() => stubCiqual())
  afterEach(() => vi.unstubAllGlobals())

  test('in a recipe row, the chosen code lands in the row and nowhere else', async () => {
    const user = userEvent.setup()
    const create = renderForm('recipes', <RecipeCreate />)

    await user.type(screen.getByLabelText(/Nom/), 'Sauce')
    await user.click(screen.getByRole('button', { name: 'Ajouter' }))
    await pickTomato(user)
    await user.type(screen.getByLabelText(/Quantité/), '400')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    const sent = create.mock.calls[0][1].data as Record<string, unknown>
    expect(sent.ingredients).toEqual([{ ciqualAlimCode: TOMATO.alim_code, quantity: 400, unit: 'g' }])
    expect(sent).not.toHaveProperty('ciqualAlimCode')
  })

  test('each recipe row keeps its own code', async () => {
    const user = userEvent.setup()
    const create = renderForm('recipes', <RecipeCreate />)

    await user.type(screen.getByLabelText(/Nom/), 'Sauce')
    await user.click(screen.getByRole('button', { name: 'Ajouter' }))
    await user.click(screen.getByRole('button', { name: 'Ajouter' }))
    const [, second] = screen.getAllByLabelText(/Aliment Ciqual/)
    await user.type(second, 'tomate')
    await user.click(await screen.findByRole('option', { name: new RegExp(TOMATO.alim_name_fr, 'i') }, { timeout: 5_000 }))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    const ingredients = create.mock.calls[0][1].data.ingredients as Array<{ ciqualAlimCode?: string | null }>
    expect(ingredients[0].ciqualAlimCode ?? null).toBeNull()
    expect(ingredients[1].ciqualAlimCode).toBe(TOMATO.alim_code)
  })

  test('outside an array, the code stays at the root of the ingredient form', async () => {
    const user = userEvent.setup()
    const create = renderForm('ingredients', <IngredientCreate />)

    await user.type(screen.getByLabelText(/Nom/), 'Tomate')
    await user.click(screen.getByRole('combobox', { name: /Catégorie/ }))
    await user.click(await screen.findByRole('option', { name: 'Fruits & Légumes' }))
    await pickTomato(user)
    await user.click(await screen.findByText('Remplir les macros depuis Ciqual'))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    const sent = create.mock.calls[0][1].data as Record<string, unknown>
    expect(sent.ciqualAlimCode).toBe(TOMATO.alim_code)
    expect(sent.kcalPer100g).toBe(TOMATO.kcal_per100g)
  })
})
