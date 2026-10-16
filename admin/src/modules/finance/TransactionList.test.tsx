import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { TransactionList } from './TransactionList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const TRANSACTION = {
  id: '/api/transactions/01T',
  label: 'Courses',
  amountCents: -4250,
  currency: 'EUR',
  bookedAt: '2026-10-10T00:00:00+00:00',
  status: 'committed',
  isExceptional: false,
  account: '/api/accounts/01BOU',
  category: '/api/categories/01CAT',
}

const ACCOUNTS = [{ id: '/api/accounts/01BOU', name: 'Compte courant', bank: 'Boursorama', currency: 'EUR' }]

describe('TransactionList', () => {
  test('names the account of each transaction as bank · currency · name', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    const dataProvider = testDataProvider({
      getList: (() => Promise.resolve({ data: [TRANSACTION], total: 1 })) as unknown as DataProvider['getList'],
      getMany: ((resource: string) =>
        Promise.resolve({ data: resource === 'accounts' ? ACCOUNTS : [] })) as unknown as DataProvider['getMany'],
      getManyReference: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getManyReference'],
    })

    render(
      <MemoryRouter>
        <AdminContext dataProvider={dataProvider}>
          <ResourceContextProvider value="transactions">
            <TransactionList />
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Boursorama · EUR · Compte courant')).toBeInTheDocument()
  })
})
