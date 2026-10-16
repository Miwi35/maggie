import { describe, expect, test } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { RecipeShow } from './RecipeShow'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const named = (id: string, name: string) => ({ id, name })

const recipe = {
  id: '01R',
  name: 'Pâtes aux légumes',
  servings: 4,
  tags: ['rapide'],
  notes: null,
  ingredients: [
    { id: '01L1', ingredientName: 'Pâtes', quantity: 200, unit: 'g' },
    { id: '01L2', ingredientName: 'Courgettes', quantity: 2, unit: 'piece' },
  ],
  utensils: [named('01U1', 'Casserole'), named('01U2', 'Poêle')],
  steps: [
    { id: 's1', position: 1, text: "Mettre l'eau à bouillir", durationMinutes: 10, background: true, ingredients: [], utensils: [named('01U1', 'Casserole')], waitsFor: [] },
    { id: 's2', position: 2, text: 'Couper les légumes', durationMinutes: null, background: false, ingredients: [named('01I2', 'Courgettes')], utensils: [], waitsFor: [] },
    { id: 's3', position: 3, text: 'Cuire les pâtes', durationMinutes: 12, background: true, ingredients: [named('01I1', 'Pâtes')], utensils: [], waitsFor: [1] },
    { id: 's4', position: 4, text: 'Faire revenir les légumes', durationMinutes: 8, background: false, ingredients: [], utensils: [named('01U2', 'Poêle')], waitsFor: [] },
    { id: 's5', position: 5, text: 'Égoutter, mélanger', durationMinutes: null, background: false, ingredients: [], utensils: [], waitsFor: [3, 4] },
  ],
}

const renderShow = (record: Record<string, unknown>) =>
  render(
    <MemoryRouter initialEntries={['/recipes/01R/show']}>
      <AdminContext dataProvider={testDataProvider({ getOne: () => Promise.resolve({ data: record }) as never })} i18nProvider={i18nProvider}>
        <Routes>
          <Route
            path="/recipes/:id/show"
            element={
              <ResourceContextProvider value="recipes">
                <RecipeShow />
              </ResourceContextProvider>
            }
          />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

describe('RecipeShow', () => {
  test('shows the equipment, then the numbered steps with what each one needs', async () => {
    renderShow(recipe)

    expect(await screen.findByRole('heading', { name: 'Pâtes aux légumes' })).toBeInTheDocument()
    const equipment = screen.getByRole('list', { name: 'Matériel' })
    expect(within(equipment).getByText('Casserole')).toBeInTheDocument()
    expect(within(equipment).getByText('Poêle')).toBeInTheDocument()

    const steps = screen.getByRole('heading', { name: 'Étapes' }).parentElement as HTMLElement
    const items = within(steps).getAllByRole('listitem').filter((item) => item.tagName === 'LI' && item.parentElement?.tagName === 'OL')
    expect(items).toHaveLength(5)
    expect(items[0]).toHaveTextContent("1. Mettre l'eau à bouillir")
    expect(within(items[0]).getByText('⏱ 10 min')).toBeInTheDocument()
    expect(within(items[0]).getByText('en fond')).toBeInTheDocument()
    expect(within(items[0]).getByText('Casserole')).toBeInTheDocument()
    expect(within(items[1]).getByText('Courgettes')).toBeInTheDocument()
    expect(within(items[1]).queryByText('en fond')).not.toBeInTheDocument()
    expect(within(items[2]).getByText('attend 1')).toBeInTheDocument()
    expect(within(items[3]).getByText('⏱ 8 min')).toBeInTheDocument()
    expect(within(items[3]).queryByText('en fond')).not.toBeInTheDocument()
    expect(within(items[4]).getByText('attend 3, 4')).toBeInTheDocument()
  })

  test('shows the ingredients with their unit', async () => {
    renderShow(recipe)

    expect(await screen.findByText('200 g Pâtes')).toBeInTheDocument()
    expect(screen.getByText('2 pièce Courgettes')).toBeInTheDocument()
  })

  test('a recipe without equipment or steps shows neither section', async () => {
    renderShow({ id: '01R', name: 'Salade', servings: 1, tags: [], ingredients: [], utensils: [], steps: [] })

    expect(await screen.findByRole('heading', { name: 'Salade' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Matériel' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Étapes' })).not.toBeInTheDocument()
  })
})
