import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { StoreList } from './StoreList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'StoreList',
  screen: () => <StoreList />,
  resource: 'stores',
  topic: '/api/stores/{id}',
  listed: { id: '/api/stores/01STORE', name: 'Carrefour', description: null, visitOrder: 1 },
  listedText: 'Carrefour',
  created: { payload: { name: 'Biocoop', description: null, visitOrder: 2 }, text: 'Biocoop' },
  updated: { payload: { name: 'Carrefour Market' }, text: 'Carrefour Market' },
})
