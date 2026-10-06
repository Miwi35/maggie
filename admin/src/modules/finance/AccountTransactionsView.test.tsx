import { describe, test, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { AccountTransactionsEmpty, AccountTransactionsView } from './AccountTransactionsView'

const ACCOUNT_IRI = '/api/accounts/01ABC'

const emptyProvider = testDataProvider({
  getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
  getOne: (() =>
    Promise.resolve({
      data: { id: ACCOUNT_IRI, name: 'Compte joint' },
    })) as unknown as DataProvider['getOne'],
})

const LocationState = () => {
  const { pathname, state } = useLocation()
  return <pre data-testid="location">{JSON.stringify({ pathname, state })}</pre>
}

describe('AccountTransactionsEmpty', () => {
  test('says the account is empty and offers the button that adds an operation', () => {
    render(
      <AdminContext dataProvider={emptyProvider}>
        <ResourceContextProvider value="transactions">
          <AccountTransactionsEmpty accountIri={ACCOUNT_IRI} />
        </ResourceContextProvider>
      </AdminContext>,
    )

    expect(screen.getByText('Aucune opération sur ce compte')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ajouter une opération' })).toBeInTheDocument()
  })
})

describe('AccountTransactionsView', () => {
  test('an account with no operation still shows the button, and it preselects the account', async () => {
    const user = userEvent.setup()

    render(
      <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
        <AdminContext dataProvider={emptyProvider}>
          <Routes>
            <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
            <Route path="*" element={<LocationState />} />
          </Routes>
        </AdminContext>
      </MemoryRouter>,
    )

    // Once the empty state replaces the list, the toolbar and its button are gone:
    // the one button left is the empty state's own.
    const placeholder = (await screen.findByText('Aucune opération sur ce compte')).parentElement!
    const button = within(placeholder).getByRole('link', { name: 'Ajouter une opération' })
    expect(screen.getAllByRole('link', { name: 'Ajouter une opération' })).toHaveLength(1)

    await user.click(button)

    const location = JSON.parse((await screen.findByTestId('location')).textContent ?? '{}')
    expect(location.pathname).toBe('/transactions/create')
    expect(location.state.record).toEqual({ account: ACCOUNT_IRI })
  })
})
