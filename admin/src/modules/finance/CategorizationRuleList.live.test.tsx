import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { CategorizationRuleList } from './CategorizationRuleList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'CategorizationRuleList',
  screen: () => <CategorizationRuleList />,
  resource: 'categorization_rules',
  topic: '/api/recurring_operations/{id}',
  listed: {
    id: '/api/recurring_operations/01RULE',
    labelPattern: 'CARREFOUR',
    matchType: 'contains',
    direction: 'debit',
    minAmountCents: null,
    maxAmountCents: null,
    priority: 10,
    isActive: true,
    category: '/api/categories/01CATEGORY',
  },
  listedText: 'CARREFOUR',
  created: {
    payload: {
      labelPattern: 'BOULANGERIE',
      matchType: 'contains',
      direction: 'debit',
      minAmountCents: null,
      maxAmountCents: null,
      priority: 5,
      isActive: true,
      period: null,
      categoryId: '01CATEGORY',
    },
    text: 'BOULANGERIE',
  },
  updated: { payload: { labelPattern: 'CARREFOUR CITY' }, text: 'CARREFOUR CITY' },
})
