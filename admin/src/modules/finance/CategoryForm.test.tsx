import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { CategoryCreate } from './CategoryCreate'
import { CategoryEdit } from './CategoryEdit'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const RENTE = {
  id: '/api/categories/1',
  name: 'Loyers perçus',
  obligation: 'income',
  passiveIncome: true,
}

/** The create screen, and the `create` the data provider received. */
const renderCreate = () => {
  const create = vi.fn().mockResolvedValue({ data: { id: '/api/categories/1' } })
  // The parent picker asks for the list; without it the harness logs the
  // unimplemented call on every render.
  const getList = vi.fn().mockResolvedValue({ data: [], total: 0 })
  render(
    <MemoryRouter>
      <AdminContext
        dataProvider={testDataProvider({ create, getList })}
        i18nProvider={i18nProvider}
      >
        <ResourceContextProvider value="categories">
          <CategoryCreate />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

  return create
}

/** The edit screen of a category already declared a rente. */
const renderEdit = () => {
  // The id in the route is the record's own — an IRI, as the API Platform data
  // provider gives them — and it has to match what `getOne` returns.
  render(
    <MemoryRouter initialEntries={[`/categories/${encodeURIComponent(RENTE.id)}`]}>
      <AdminContext
        dataProvider={testDataProvider({
          getOne: (() => Promise.resolve({ data: RENTE })) as unknown as DataProvider['getOne'],
          getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
        })}
        i18nProvider={i18nProvider}
      >
        <ResourceContextProvider value="categories">
          {/* Through a route: `useEditController` reads the record's id from
              the `:id` parameter, and there is no id prop to give it. */}
          <Routes>
            <Route path="/categories/:id" element={<CategoryEdit />} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
}

const chooseObligation = async (user: ReturnType<typeof userEvent.setup>, label: string) => {
  await user.click(await screen.findByLabelText(/Obligation/))
  await user.click(screen.getByRole('option', { name: label }))
}

describe('CategoryForm', () => {
  /**
   * A rente is money coming in; the API refuses the flag anywhere else, so a
   * box offered on a dépense would be a trap.
   */
  test('offers the rente box only once the category is a recette', async () => {
    const user = userEvent.setup()
    renderCreate()

    expect(await screen.findByLabelText(/Obligation/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Rente')).not.toBeInTheDocument()

    await chooseObligation(user, 'Recette')

    expect(await screen.findByLabelText('Rente')).toBeInTheDocument()
    expect(
      screen.getByText(/compteur d'indépendance compare à votre train de vie/),
    ).toBeInTheDocument()
  })

  test('a rente declared on a recette is what the API is sent', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(await screen.findByLabelText(/Nom/), 'Loyers perçus')
    await chooseObligation(user, 'Recette')
    await user.click(await screen.findByLabelText('Rente'))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({
      name: 'Loyers perçus',
      obligation: 'income',
      passiveIncome: true,
    })
  })

  /**
   * The flag must not survive the input that set it: react-hook-form keeps the
   * value of an unmounted input, and a rente sent with a spending obligation
   * is a 422 on a field no longer on screen — nothing the user can correct.
   */
  test('reclassifying the category takes the rente flag away with the box', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(await screen.findByLabelText(/Nom/), 'Loisirs')
    await chooseObligation(user, 'Recette')
    await user.click(await screen.findByLabelText('Rente'))

    await chooseObligation(user, 'Non-obligatoire')

    expect(screen.queryByLabelText('Rente')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({
      obligation: 'optional',
      passiveIncome: false,
    })
  })

  /**
   * The edit screen is where the reset runs against a record that is already a
   * rente: it must not wipe the flag on the way in.
   */
  test('an existing rente keeps its flag through an edit', async () => {
    const user = userEvent.setup()
    renderEdit()

    expect(await screen.findByLabelText('Rente')).toBeChecked()

    // And it stays: the reset watches the obligation, which this change does
    // not touch. What the save then sends is `renderCreate`'s business — the
    // edit screen's mutation is undoable, so the data provider is only called
    // after the undo window, which no assertion should wait on.
    await user.type(screen.getByLabelText(/Nom/), ' et garages')

    expect(screen.getByLabelText('Rente')).toBeChecked()
  })
})
