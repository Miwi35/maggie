import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { CategorizationRuleList } from './CategorizationRuleList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'CategorizationRuleList',
  screen: () => <CategorizationRuleList />,
  resource: 'categorization_rules',
  topic: '/api/categorization_rules/{id}',
  listed: {
    id: '/api/categorization_rules/01RULE',
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
      categoryId: '01CATEGORY',
    },
    text: 'BOULANGERIE',
  },
  updated: { payload: { labelPattern: 'CARREFOUR CITY' }, text: 'CARREFOUR CITY' },
})
