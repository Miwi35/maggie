import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { CategoryCreate } from './CategoryCreate'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

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
})
