import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { RecipeList } from './RecipeList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'RecipeList',
  screen: () => <RecipeList />,
  resource: 'recipes',
  topic: '/api/recipes/{id}',
  listed: {
    id: '/api/recipes/01RECIPE',
    name: 'Ratatouille',
    servings: 4,
    tags: [],
    ingredients: [],
    createdAt: '2026-10-01T10:00:00+00:00',
  },
  listedText: 'Ratatouille',
  created: {
    payload: { name: 'Gratin dauphinois', servings: 6, tags: ['plat'], notes: null, ingredients: [] },
    text: 'Gratin dauphinois',
  },
  updated: { payload: { name: 'Ratatouille provençale' }, text: 'Ratatouille provençale' },
})
