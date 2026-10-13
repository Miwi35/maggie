import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { LoanList } from './LoanList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'LoanList',
  screen: () => <LoanList />,
  resource: 'loans',
  topic: '/api/loans/{id}',
  listed: {
    id: '/api/loans/01LOAN',
    name: 'Prêt immobilier',
    lender: 'Crédit Agricole',
    principalRemainingCents: 18000000,
    monthlyPaymentCents: 95000,
    annualRateBasisPoints: 150,
    priority: 1,
    currency: 'EUR',
  },
  listedText: 'Prêt immobilier',
  created: {
    payload: {
      name: 'Prêt auto',
      lender: 'Cetelem',
      principalRemainingCents: 800000,
      monthlyPaymentCents: 21000,
      annualRateBasisPoints: 420,
      priority: 2,
      currency: 'EUR',
    },
    text: 'Prêt auto',
  },
  updated: { payload: { name: 'Prêt immobilier principal' }, text: 'Prêt immobilier principal' },
})
