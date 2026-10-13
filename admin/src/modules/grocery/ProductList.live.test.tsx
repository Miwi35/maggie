import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { ProductList } from './ProductList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'ProductList',
  screen: () => <ProductList />,
  resource: 'products',
  topic: '/api/products/{id}',
  listed: {
    id: '/api/products/01PRODUCT',
    name: 'Lait',
    category: 'dairy',
    defaultUnit: 'l',
    stockState: 'in_stock',
    preferredStore: '/api/stores/01STORE',
  },
  listedText: 'Lait',
  created: {
    payload: {
      name: 'Beurre doux',
      category: 'dairy',
      stockState: 'in_stock',
      preferredStoreId: '01STORE',
      fallbackStoreId: null,
    },
    text: 'Beurre doux',
  },
  updated: { payload: { name: 'Lait demi-écrémé' }, text: 'Lait demi-écrémé' },
})
