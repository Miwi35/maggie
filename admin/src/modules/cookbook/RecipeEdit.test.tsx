import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { RecipeEdit } from './RecipeEdit'

// The hub is not under test here: the hook is replaced by one that hands the test the callback
// the sheet registered, so a test can play what the hub would deliver.
let deliver: (data?: string) => void = () => {}
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], onMessage: (data?: string) => void) => {
    deliver = onMessage
  },
}))

const publish = (payload: Record<string, unknown>) =>
  act(() => deliver(JSON.stringify({ '@id': '/api/recipes/01R', ...payload })))

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

  describe('when the recipe changes elsewhere', () => {
    const line = (quantity: number) => ({
      id: '01L1',
      ingredient: { '@id': '/api/ingredients/01I1', id: '01I1', name: 'Pâtes', ciqualAlimCode: '9810' },
      ingredientName: 'Pâtes',
      ciqualAlimCode: '9810',
      quantity,
      unit: 'g',
    })

    test('shows the new quantity without a reload', async () => {
      renderEdit(vi.fn())
      expect(await screen.findByLabelText(/Quantité/)).toHaveValue(200)

      publish({ ingredients: [line(350)] })

      await waitFor(() => expect(screen.getByLabelText(/Quantité/)).toHaveValue(350))
      expect(screen.queryByText(/modifiée ailleurs/)).not.toBeInTheDocument()
    })

    test('shows new notes, tags and name, and a line added', async () => {
      renderEdit(vi.fn())
      await screen.findByLabelText(/Quantité/)

      publish({
        name: 'Pâtes bolognaise',
        notes: 'Ajouter du basilic',
        tags: ['pasta', 'rapide'],
        ingredients: [line(200), { ...line(3), id: '01L2', unit: 'piece' }],
      })

      await waitFor(() => expect(screen.getByLabelText('Nom *')).toHaveValue('Pâtes bolognaise'))
      expect(screen.getByLabelText('Notes')).toHaveValue('Ajouter du basilic')
      expect(screen.getByLabelText(/Tags/)).toHaveValue('pasta, rapide')
      expect(screen.getAllByLabelText(/Quantité/)).toHaveLength(2)
    })

    test('does not overwrite the field being edited, updates the others, and offers to reload', async () => {
      const user = userEvent.setup()
      renderEdit(vi.fn())
      const name = await screen.findByLabelText('Nom *')
      await user.clear(name)
      await user.type(name, 'Mes pâtes')

      publish({ name: 'Pâtes bolognaise', notes: 'Ajouter du basilic' })

      expect(await screen.findByText(/modifiée ailleurs/)).toBeInTheDocument()
      expect(screen.getByLabelText('Nom *')).toHaveValue('Mes pâtes')
      expect(screen.getByLabelText('Notes')).toHaveValue('Ajouter du basilic')

      await user.click(screen.getByRole('button', { name: 'Recharger' }))

      expect(screen.getByLabelText('Nom *')).toHaveValue('Pâtes bolognaise')
      expect(screen.queryByText(/modifiée ailleurs/)).not.toBeInTheDocument()
    })

    test('does not warn when the published value is the one being typed', async () => {
      const user = userEvent.setup()
      renderEdit(vi.fn())
      const name = await screen.findByLabelText('Nom *')
      await user.clear(name)
      await user.type(name, 'Pâtes bolognaise')

      publish({ name: 'Pâtes bolognaise' })

      expect(screen.getByLabelText('Nom *')).toHaveValue('Pâtes bolognaise')
      expect(screen.queryByText(/modifiée ailleurs/)).not.toBeInTheDocument()
    })

    test('keeps the whole ingredient list being edited when the list changes elsewhere', async () => {
      const user = userEvent.setup()
      renderEdit(vi.fn())
      const quantity = await screen.findByLabelText(/Quantité/)
      await user.clear(quantity)
      await user.type(quantity, '120')

      publish({ ingredients: [line(350), { ...line(3), id: '01L2', unit: 'piece' }] })

      expect(await screen.findByText(/modifiée ailleurs/)).toBeInTheDocument()
      expect(screen.getAllByLabelText(/Quantité/)).toHaveLength(1)
      expect(screen.getByLabelText(/Quantité/)).toHaveValue(120)

      await user.click(screen.getByRole('button', { name: 'Recharger' }))

      await waitFor(() => expect(screen.getAllByLabelText(/Quantité/)).toHaveLength(2))
      expect(screen.getAllByLabelText(/Quantité/)[0]).toHaveValue(350)
    })

    test('sends what the sheet shows, not the version it opened with', async () => {
      const user = userEvent.setup()
      const update = vi.fn().mockResolvedValue({ data: recipe })
      renderEdit(update)
      await screen.findByLabelText(/Quantité/)

      publish({ ingredients: [line(350)] })
      await waitFor(() => expect(screen.getByLabelText(/Quantité/)).toHaveValue(350))
      await user.type(screen.getByLabelText('Notes'), 'Sans sel')
      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(update).toHaveBeenCalled())
      expect(update.mock.calls[0][1].data.ingredients[0]).toMatchObject({ ciqualAlimCode: '9810', quantity: 350 })
    })

    test('ignores the update of another recipe and the deletion message', async () => {
      renderEdit(vi.fn())
      await screen.findByLabelText(/Quantité/)

      act(() => deliver(JSON.stringify({ '@id': '/api/recipes/01OTHER', name: 'Tarte' })))
      publish({ deleted: true })
      act(() => deliver('not json'))

      expect(screen.getByLabelText('Nom *')).toHaveValue('Pâtes à la tomate')
    })
  })
})
