import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { CategoryList } from './CategoryList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'CategoryList',
  screen: () => <CategoryList />,
  resource: 'categories',
  topic: '/api/categories/{id}',
  listed: { id: '/api/categories/01CATEGORY', name: 'Alimentation', obligation: 'mandatory', parent: null, color: null },
  listedText: 'Alimentation',
  created: {
    payload: { name: 'Loisirs', obligation: 'discretionary', passiveIncome: false, parentId: null, color: '#ff8800' },
    text: 'Loisirs',
  },
  updated: { payload: { name: 'Courses' }, text: 'Courses' },
})
