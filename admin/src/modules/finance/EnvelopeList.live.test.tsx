import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { EnvelopeList } from './EnvelopeList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'EnvelopeList',
  screen: () => <EnvelopeList />,
  resource: 'envelopes',
  topic: '/api/envelopes/{id}',
  listed: {
    id: '/api/envelopes/01ENVELOPE',
    mode: 'annual',
    year: 2026,
    month: null,
    amountCents: 120000,
    currency: 'EUR',
    category: '/api/categories/01CATEGORY',
  },
  listedText: 'Année 2026',
  created: {
    payload: { mode: 'annual', year: 2027, month: null, amountCents: 90000, currency: 'EUR', categoryId: '01CATEGORY' },
    text: 'Année 2027',
  },
  updated: { payload: { mode: 'monthly', month: 7 }, text: 'Juillet 2026' },
})
