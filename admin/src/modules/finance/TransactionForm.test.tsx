import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Edit, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { TransactionCreate } from './TransactionCreate'
import { TransactionForm, toTransactionPayload } from './TransactionForm'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const CATEGORIES = [
  { id: '/api/categories/salaire', name: 'Salaire', obligation: 'income' },
  { id: '/api/categories/netflix', name: 'Netflix', obligation: 'optional' },
  { id: '/api/categories/loyer', name: 'Loyer', obligation: 'mandatory' },
]

const ACCOUNTS = [{ id: '/api/accounts/1', name: 'Compte courant' }]

const getList = ((resource: string) =>
  Promise.resolve({
    data: resource === 'categories' ? CATEGORIES : ACCOUNTS,
    total: resource === 'categories' ? CATEGORIES.length : ACCOUNTS.length,
  })) as unknown as DataProvider['getList']

const getMany = ((resource: string) =>
  Promise.resolve({
    data: resource === 'categories' ? CATEGORIES : ACCOUNTS,
  })) as unknown as DataProvider['getMany']

const renderCreate = () => {
  const create = vi.fn().mockResolvedValue({ data: { id: '/api/transactions/1' } })
  render(
    <MemoryRouter>
      <AdminContext
        dataProvider={testDataProvider({ create, getList, getMany })}
        i18nProvider={i18nProvider}
      >
        <ResourceContextProvider value="transactions">
          <TransactionCreate />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
  return create
}

const renderEdit = (record: Record<string, unknown>) => {
  const update = vi.fn().mockResolvedValue({ data: record })
  render(
    <MemoryRouter initialEntries={[`/transactions/${encodeURIComponent(record.id as string)}`]}>
      <AdminContext
        dataProvider={testDataProvider({
          getOne: (() => Promise.resolve({ data: record })) as unknown as DataProvider['getOne'],
          update,
          getList,
          getMany,
        })}
        i18nProvider={i18nProvider}
      >
        <ResourceContextProvider value="transactions">
          <Routes>
            <Route path="/transactions/:id" element={<EditScreen />} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
  return update
}

/** TransactionEdit, with the undo window out of the way so the save is observable. */
const EditScreen = () => (
  <Edit mutationMode="pessimistic" transform={toTransactionPayload}>
    <TransactionForm />
  </Edit>
)

const openCategories = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByLabelText('Catégorie'))
  return screen.findAllByRole('option')
}

const fill = async (user: ReturnType<typeof userEvent.setup>, label: string, amount: string) => {
  await user.type(await screen.findByLabelText(/Libellé/), label)
  await user.type(screen.getByLabelText(/Montant/), amount)
  await user.type(screen.getByLabelText(/Date/), '2026-07-08')
}

const chooseAccount = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByLabelText(/Compte/))
  await user.click(await screen.findByRole('option', { name: 'Compte courant' }))
}

describe('TransactionForm', () => {
  test('opens on the Dépense / Recette choice, as a dépense', async () => {
    renderCreate()

    expect(await screen.findByRole('button', { name: 'Dépense' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('button', { name: 'Recette' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText('État de la dépense')).toBeInTheDocument()
    expect(screen.getByLabelText('Dépense exceptionnelle')).toBeInTheDocument()
  })

  test('a dépense offers every category but the income ones', async () => {
    const user = userEvent.setup()
    renderCreate()

    const options = await openCategories(user)

    expect(options.map((o) => o.textContent)).toEqual(['Netflix', 'Loyer'])
  })

  test('a recette offers only the income categories', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(await screen.findByRole('button', { name: 'Recette' }))
    const options = await openCategories(user)

    expect(options.map((o) => o.textContent)).toEqual(['Salaire'])
  })

  test('a recette says Reçue, Attendue, À arbitrer — never Dépensée or Engagée', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(await screen.findByRole('button', { name: 'Recette' }))

    expect(screen.getByText('État de la recette')).toBeInTheDocument()
    expect(screen.getByLabelText('Recette exceptionnelle')).toBeInTheDocument()
    expect(screen.getByText(/une prime, un remboursement/)).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: /Statut/ }))
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Reçue',
      'Attendue',
      'À arbitrer',
    ])
    expect(screen.queryByText('Dépensée')).not.toBeInTheDocument()
    expect(screen.queryByText('Engagée')).not.toBeInTheDocument()
  })

  test('a dépense says Dépensée, Engagée, Planifiée, À arbitrer', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(await screen.findByRole('combobox', { name: /Statut/ }))
    const listbox = await screen.findByRole('listbox')

    expect(within(listbox).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Dépensée',
      'Engagée',
      'Planifiée',
      'À arbitrer',
    ])
  })

  test('changing the nature empties a category that no longer fits', async () => {
    const user = userEvent.setup()
    renderCreate()

    await openCategories(user)
    await user.click(await screen.findByRole('option', { name: 'Netflix' }))
    expect(screen.getByLabelText('Catégorie')).toHaveValue('Netflix')

    await user.click(screen.getByRole('button', { name: 'Recette' }))

    await waitFor(() => expect(screen.getByLabelText('Catégorie')).toHaveValue(''))
  })

  test('a dépense is sent with a negative amount, though it is typed without a sign', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await chooseAccount(user)
    await fill(user, 'Boulangerie', '15.99')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    const data = create.mock.calls[0][1].data
    expect(data).toMatchObject({ label: 'Boulangerie', amountCents: -1599, status: 'spent' })
    expect(data).not.toHaveProperty('nature')
  })

  test('a recette is sent with a positive amount and the Reçue status', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.click(await screen.findByRole('button', { name: 'Recette' }))
    await chooseAccount(user)
    await fill(user, 'Salaire juillet', '2350')
    await openCategories(user)
    await user.click(await screen.findByRole('option', { name: 'Salaire' }))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({
      label: 'Salaire juillet',
      amountCents: 235000,
      status: 'spent',
      category: '/api/categories/salaire',
    })
  })

  test('editing an existing recette opens as a recette, with its amount unsigned', async () => {
    const update = renderEdit({
      id: '/api/transactions/9',
      account: '/api/accounts/1',
      label: 'Prime',
      amountCents: 50000,
      bookedAt: '2026-07-20',
      currency: 'EUR',
      status: 'planned',
      isExceptional: true,
      category: '/api/categories/salaire',
    })
    const user = userEvent.setup()

    await waitFor(() => expect(screen.getByLabelText(/Libellé/)).toHaveValue('Prime'))
    expect(screen.getByRole('button', { name: 'Recette' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('État de la recette')).toBeInTheDocument()
    expect(screen.getByLabelText('Recette exceptionnelle')).toBeChecked()
    expect(screen.getByLabelText(/Montant/)).toHaveValue(500)
    expect(screen.getByRole('combobox', { name: /Statut/ })).toHaveTextContent('Attendue')

    await user.type(screen.getByLabelText(/Libellé/), ' de juillet')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data).toMatchObject({ amountCents: 50000, status: 'planned' })
  })

  test('editing a stored dépense shows the amount without its minus and keeps the sign', async () => {
    const update = renderEdit({
      id: '/api/transactions/8',
      account: '/api/accounts/1',
      label: 'Loyer',
      amountCents: -65000,
      bookedAt: '2026-07-01',
      currency: 'EUR',
      status: 'spent',
      isExceptional: false,
      category: '/api/categories/loyer',
    })
    const user = userEvent.setup()

    await waitFor(() => expect(screen.getByLabelText(/Libellé/)).toHaveValue('Loyer'))
    expect(screen.getByRole('button', { name: 'Dépense' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText(/Montant/)).toHaveValue(650)

    await user.type(screen.getByLabelText(/Libellé/), ' juillet')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data).toMatchObject({ amountCents: -65000 })
  })

  test('a committed status does not survive the switch to recette', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await chooseAccount(user)
    await fill(user, 'Remboursement', '40')
    await user.click(screen.getByRole('combobox', { name: /Statut/ }))
    await user.click(await screen.findByRole('option', { name: 'Engagée' }))
    await user.click(screen.getByRole('button', { name: 'Recette' }))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({ amountCents: 4000, status: 'planned' })
  })
})
