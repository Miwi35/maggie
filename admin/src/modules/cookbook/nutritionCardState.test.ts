import { describe, expect, test } from 'vitest'
import { composeNutritionState } from './nutritionCardState'

const meal = (id: string, slot: 'lunch' | 'dinner', extra: Record<string, unknown> = {}) => ({ id, slot, summary: id, ...extra })
const need = (mealId: string, productId: string, name: string) => ({ productId, name, mealId })

describe('composeNutritionState', () => {
  test('always has lunch then dinner, an empty slot with no meal', () => {
    expect(composeNutritionState([], [])).toEqual({
      slots: [
        { slot: 'lunch', meals: [] },
        { slot: 'dinner', meals: [] },
      ],
    })
  })

  test('puts each meal in its slot with its title, guests and the names of its recipes', () => {
    const state = composeNutritionState(
      [meal('m1', 'dinner', { guests: 4, recipeNames: ['Pâtes', 'Salade'] }), meal('m2', 'lunch', { summary: 'Restaurant' })],
      [],
    )

    expect(state.slots[1].meals).toEqual([{ id: 'm1', title: 'm1', guests: 4, recipes: ['Pâtes', 'Salade'], missing: [] }])
    expect(state.slots[0].meals).toEqual([{ id: 'm2', title: 'Restaurant', guests: 1, recipes: [], missing: [] }])
  })

  test('attaches what is missing to its own meal, once per product', () => {
    const state = composeNutritionState(
      [meal('m1', 'lunch'), meal('m2', 'dinner')],
      [need('m1', 'p1', 'Parmesan'), need('m1', 'p1', 'Parmesan'), need('m1', 'p2', 'Basilic'), need('m2', 'p1', 'Parmesan')],
    )

    expect(state.slots[0].meals[0].missing).toEqual(['Parmesan', 'Basilic'])
    expect(state.slots[1].meals[0].missing).toEqual(['Parmesan'])
  })

  test('ignores a need whose meal is not in the day', () => {
    const state = composeNutritionState([meal('m1', 'lunch')], [need('gone', 'p1', 'Parmesan')])

    expect(state.slots[0].meals[0].missing).toEqual([])
  })
})
