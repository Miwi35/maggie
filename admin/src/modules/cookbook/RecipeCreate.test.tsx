import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { RecipeCreate } from './RecipeCreate'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const renderCreate = (create: DataProvider['create']) =>
  render(
    <MemoryRouter initialEntries={['/recipes/create']}>
      <AdminContext
        dataProvider={testDataProvider({
          create,
          getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
          getMany: (() => Promise.resolve({ data: [] })) as unknown as DataProvider['getMany'],
        })}
        i18nProvider={i18nProvider}
      >
        <Routes>
          <Route
            path="/recipes/create"
            element={
              <ResourceContextProvider value="recipes">
                <RecipeCreate />
              </ResourceContextProvider>
            }
          />
          <Route path="/recipes" element={<p>la liste</p>} />
          <Route path="/recipes/:id/show" element={<p>la fiche</p>} />
        </Routes>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

describe('RecipeCreate', { timeout: 30_000 }, () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) }))))
  afterEach(() => vi.unstubAllGlobals())

  test('opens the sheet empty, with the name popin on top and the blocks not yet editable', async () => {
    renderCreate(vi.fn() as unknown as DataProvider['create'])

    expect(await screen.findByRole('dialog', { name: 'Nouvelle recette' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Nouvelle recette', level: 1, hidden: true })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ajouter un ingrédient', hidden: true })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Ajouter une étape', hidden: true })).toBeDisabled()
  })

  test('creates the recipe from the popin with an empty body otherwise, then opens its sheet', async () => {
    const user = userEvent.setup()
    const create = vi.fn(() => Promise.resolve({ data: { id: '/api/recipes/01NEW' } }))
    renderCreate(create as unknown as DataProvider['create'])

    await user.type(await screen.findByLabelText(/^Nom/), 'Soupe')
    await user.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create).toHaveBeenCalledWith('recipes', { data: { ingredients: [], utensils: [], steps: [], name: 'Soupe', servings: 4, tags: [] } })
    expect(await screen.findByText('la fiche')).toBeInTheDocument()
  })

  test('cannot be created without a name', async () => {
    renderCreate(vi.fn() as unknown as DataProvider['create'])

    expect(await screen.findByRole('button', { name: 'Créer' })).toBeDisabled()
  })

  test('an API refusal stays in the popin', async () => {
    const user = userEvent.setup()
    const create = vi.fn(() => Promise.reject(Object.assign(new Error('Nom déjà pris'), { status: 400, body: { 'hydra:description': 'Nom déjà pris' } })))
    renderCreate(create as unknown as DataProvider['create'])

    await user.type(await screen.findByLabelText(/^Nom/), 'Soupe')
    await user.click(screen.getByRole('button', { name: 'Créer' }))

    expect(await screen.findByText('Nom déjà pris')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  test('cancelling goes back to the list and creates nothing', async () => {
    const user = userEvent.setup()
    const create = vi.fn()
    renderCreate(create as unknown as DataProvider['create'])

    await user.click(await screen.findByRole('button', { name: 'Annuler' }))

    expect(await screen.findByText('la liste')).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })
})
