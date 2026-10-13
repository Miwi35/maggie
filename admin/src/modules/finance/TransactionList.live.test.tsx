import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { TransactionList } from './TransactionList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'TransactionList',
  screen: () => <TransactionList />,
  resource: 'transactions',
  topic: '/api/transactions/{id}',
  listed: {
    id: '/api/transactions/01TRANSACTION',
    label: 'Courses Carrefour',
    amountCents: -4250,
    currency: 'EUR',
    bookedAt: '2026-10-10T00:00:00+00:00',
    status: 'committed',
    isExceptional: false,
    account: '/api/accounts/01ACCOUNT',
    category: '/api/categories/01CATEGORY',
  },
  listedText: 'Courses Carrefour',
  created: {
    payload: {
      label: 'Boulangerie',
      amountCents: -320,
      currency: 'EUR',
      bookedAt: '2026-10-12',
      status: 'committed',
      isExceptional: false,
      accountId: '01ACCOUNT',
      categoryId: null,
    },
    text: 'Boulangerie',
  },
  updated: { payload: { label: 'Courses Carrefour City' }, text: 'Courses Carrefour City' },
})
