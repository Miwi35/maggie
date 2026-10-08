import { describe, test, expect, vi, afterEach } from 'vitest'
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
  test('says the account is empty and offers the button that adds a transaction', () => {
    render(
      <AdminContext dataProvider={emptyProvider}>
        <ResourceContextProvider value="transactions">
          <AccountTransactionsEmpty accountIri={ACCOUNT_IRI} />
        </ResourceContextProvider>
      </AdminContext>,
    )

    expect(screen.getByText('Aucune transaction sur ce compte')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ajouter une transaction' })).toBeInTheDocument()
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
    const placeholder = (await screen.findByText('Aucune transaction sur ce compte')).parentElement!
    const button = within(placeholder).getByRole('link', { name: 'Ajouter une transaction' })
    expect(screen.getAllByRole('link', { name: 'Ajouter une transaction' })).toHaveLength(1)

    await user.click(button)

    const location = JSON.parse((await screen.findByTestId('location')).textContent ?? '{}')
    expect(location.pathname).toBe('/transactions/create')
    expect(location.state.record).toEqual({ account: ACCOUNT_IRI })
  })

  test('a recette reads Reçue or Attendue in the list, a dépense Dépensée — never the other way round', async () => {
    const rows = [
      { id: '/api/transactions/1', label: 'Salaire', amountCents: 235000, currency: 'EUR', status: 'spent', bookedAt: '2026-07-31' },
      { id: '/api/transactions/2', label: 'Prime', amountCents: 50000, currency: 'EUR', status: 'planned', bookedAt: '2026-08-15' },
      { id: '/api/transactions/3', label: 'Netflix', amountCents: -1399, currency: 'EUR', status: 'spent', bookedAt: '2026-07-03' },
    ]
    const provider = testDataProvider({
      getList: (() => Promise.resolve({ data: rows, total: rows.length })) as unknown as DataProvider['getList'],
      getOne: (() =>
        Promise.resolve({ data: { id: ACCOUNT_IRI, name: 'Compte joint' } })) as unknown as DataProvider['getOne'],
    })

    render(
      <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
        <AdminContext dataProvider={provider}>
          <Routes>
            <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
          </Routes>
        </AdminContext>
      </MemoryRouter>,
    )

    const rowOf = async (label: string) => (await screen.findByText(label)).closest('tr')!

    expect(within(await rowOf('Salaire')).getByText('Reçue')).toBeInTheDocument()
    expect(within(await rowOf('Prime')).getByText('Attendue')).toBeInTheDocument()
    expect(within(await rowOf('Netflix')).getByText('Dépensée')).toBeInTheDocument()
    expect(screen.getAllByText('Dépensée')).toHaveLength(1)
  })

  describe('internal transfers', () => {
    const rows = [
      {
        id: '/api/transactions/01OUT',
        label: 'Virement vers Courant',
        amountCents: -300000,
        currency: 'EUR',
        status: 'spent',
        bookedAt: '2026-09-12',
        transferKind: 'internal',
        transferSource: 'auto',
        counterpart: '/api/transactions/01IN',
      },
      {
        id: '/api/transactions/01SHOP',
        label: 'Supermarché',
        amountCents: -8000,
        currency: 'EUR',
        status: 'spent',
        bookedAt: '2026-09-14',
        transferKind: 'none',
        transferSource: 'auto',
        counterpart: null,
      },
    ]
    const provider = testDataProvider({
      getList: (() => Promise.resolve({ data: rows, total: rows.length })) as unknown as DataProvider['getList'],
      getOne: (() =>
        Promise.resolve({ data: { id: ACCOUNT_IRI, name: 'Livret' } })) as unknown as DataProvider['getOne'],
    })

    afterEach(() => {
      vi.unstubAllGlobals()
    })

    const renderView = () =>
      render(
        <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
          <AdminContext dataProvider={provider}>
            <Routes>
              <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
            </Routes>
          </AdminContext>
        </MemoryRouter>,
      )

    test('a marked line carries the badge and names its counterpart; an ordinary one does not', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({
          ok: true,
          json: async () => ({
            transferKind: 'internal',
            transferSource: 'auto',
            counterpart: {
              id: '01IN',
              label: 'Virement du Livret',
              amountCents: 300000,
              currency: 'EUR',
              bookedAt: '2026-09-13',
              accountId: '01CHK',
              accountName: 'Courant',
            },
          }),
        }),
      )

      renderView()

      const marked = (await screen.findByText('Virement vers Courant')).closest('tr')!
      expect(within(marked).getByText('Virement interne')).toBeInTheDocument()
      expect(await within(marked).findByText(/Courant · 13\/09\/2026 · Virement du Livret/)).toBeInTheDocument()

      const ordinary = (await screen.findByText('Supermarché')).closest('tr')!
      expect(within(ordinary).queryByText('Virement interne')).not.toBeInTheDocument()
      expect(screen.getAllByText('Virement interne')).toHaveLength(1)
    })

    test('a line paired with nothing says the other account is not followed', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue({
          ok: true,
          json: async () => ({ transferKind: 'internal', transferSource: 'manual', counterpart: null }),
        }),
      )

      renderView()

      const marked = (await screen.findByText('Virement vers Courant')).closest('tr')!
      expect(await within(marked).findByText(/Sans contrepartie/)).toBeInTheDocument()
    })
  })
})
