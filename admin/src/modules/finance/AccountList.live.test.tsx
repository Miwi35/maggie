import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { AccountList } from './AccountList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'AccountList',
  screen: () => <AccountList />,
  resource: 'accounts',
  topic: '/api/accounts/{id}',
  listed: {
    id: '/api/accounts/01ACCOUNT',
    name: 'Compte principal',
    bank: 'Boursorama',
    type: 'checking',
    currency: 'EUR',
    balanceCents: 120000,
    isCushion: false,
  },
  listedText: 'Compte principal',
  created: {
    payload: {
      name: 'Livret A',
      bank: 'Boursorama',
      type: 'savings',
      currency: 'EUR',
      balanceCents: 500000,
      isCushion: true,
    },
    text: 'Livret A',
  },
  updated: { payload: { name: 'Compte joint' }, text: 'Compte joint' },
})
