import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { ErrandList } from './ErrandList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'ErrandList',
  screen: () => <ErrandList />,
  resource: 'errands',
  topic: '/api/errands/{id}',
  listed: {
    id: '/api/errands/01ERRAND',
    store: '/api/stores/01LIDL',
    storeName: 'Lidl',
    endedAt: '2026-10-14T18:05:00+02:00',
    itemCount: 2,
    items: [],
  },
  listedText: 'Lidl',
  created: {
    payload: { storeId: '01CARREFOUR', storeName: 'Carrefour', endedAt: '2026-10-15T10:00:00+02:00', itemCount: 3, items: [] },
    text: 'Carrefour',
  },
  updated: { payload: { storeName: 'Lidl Rennes' }, text: 'Lidl Rennes' },
})
