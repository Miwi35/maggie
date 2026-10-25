import { describe, expect, test } from 'vitest'
import type { RecipeLine, RecipeStep } from '../recipeSheet'
import { stepIngredients } from './cookingSteps'

const step = (position: number, text: string, extra: Partial<RecipeStep> = {}): RecipeStep => ({
  id: `s${position}`,
  position,
  text,
  durationMinutes: null,
  background: false,
  ingredients: [],
  utensils: [],
  waitsFor: [],
  ...extra,
})

describe('stepIngredients', () => {
  const line = (id: string, ingredientId: string, quantity: number, unit = 'g'): RecipeLine => ({
    id,
    ingredient: { id: ingredientId, name: 'x' },
    ingredientName: 'x',
    quantity,
    unit,
  })

  test('a step shows the recipe lines of its ingredients, with their quantities', () => {
    const cooked = step(1, 'Cuire', { ingredients: [{ id: 'i1', name: 'Pâtes' }] })
    const found = stepIngredients(cooked, [line('l1', 'i1', 200), line('l2', 'i2', 300)])

    expect(found).toHaveLength(1)
    expect(found[0]).toMatchObject({ name: 'Pâtes', line: { quantity: 200 } })
  })

  test('an ingredient the recipe has no line for keeps its name, without a quantity', () => {
    const cooked = step(1, 'Cuire', { ingredients: [{ id: 'i9', name: 'Sel' }, 'Poivre'] })

    expect(stepIngredients(cooked, [line('l1', 'i1', 200)]).map(({ name, line: found }) => [name, found])).toEqual([
      ['Sel', null],
      ['Poivre', null],
    ])
  })
})
