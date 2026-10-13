import { vi } from 'vitest'
import { liveListSuite } from '../../test/mercure'
import { IngredientList } from './IngredientList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

liveListSuite({
  name: 'IngredientList',
  screen: () => <IngredientList />,
  resource: 'ingredients',
  topic: '/api/ingredients/{id}',
  listed: {
    id: '/api/ingredients/01INGRED',
    name: 'Tomate',
    category: 'vegetable',
    defaultUnit: 'g',
    kcalPer100g: 18,
  },
  listedText: 'Tomate',
  created: {
    payload: { name: 'Courgette', category: 'vegetable', kcalPer100g: 17 },
    text: 'Courgette',
  },
  updated: { payload: { name: 'Tomate cerise' }, text: 'Tomate cerise' },
})
