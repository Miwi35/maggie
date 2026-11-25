import { describe, expect, test } from 'vitest'
import type { HomeMeal } from './cuisineCardState'
import { composeCuisineState, mealHeadline, mealRecipesLine, missingLabel } from './cuisineCardState'

const meal = (id: string, slot: 'lunch' | 'dinner', extra: Record<string, unknown> = {}) => ({ id, slot, summary: id, ...extra })
const need = (mealId: string, productId: string, name: string) => ({ productId, name, mealId })

describe('composeCuisineState', () => {
  test('always has lunch then dinner, an empty slot with no meal', () => {
    expect(composeCuisineState([], [])).toEqual({
      slots: [
        { slot: 'lunch', meals: [] },
        { slot: 'dinner', meals: [] },
      ],
    })
  })

  test('puts each meal in its slot with its title, guests and the names of its recipes', () => {
    const state = composeCuisineState(
      [meal('m1', 'dinner', { guests: 4, recipeNames: ['Pâtes', 'Salade'] }), meal('m2', 'lunch', { summary: 'Restaurant' })],
      [],
    )

    expect(state.slots[1].meals).toEqual([{ id: 'm1', title: 'm1', guests: 4, recipes: ['Pâtes', 'Salade'], missing: [] }])
    expect(state.slots[0].meals).toEqual([{ id: 'm2', title: 'Restaurant', guests: 1, recipes: [], missing: [] }])
  })

  test('attaches what is missing to its own meal, once per product', () => {
    const state = composeCuisineState(
      [meal('m1', 'lunch'), meal('m2', 'dinner')],
      [need('m1', 'p1', 'Parmesan'), need('m1', 'p1', 'Parmesan'), need('m1', 'p2', 'Basilic'), need('m2', 'p1', 'Parmesan')],
    )

    expect(state.slots[0].meals[0].missing).toEqual(['Parmesan', 'Basilic'])
    expect(state.slots[1].meals[0].missing).toEqual(['Parmesan'])
  })

  test('ignores a need whose meal is not in the day', () => {
    const state = composeCuisineState([meal('m1', 'lunch')], [need('gone', 'p1', 'Parmesan')])

    expect(state.slots[0].meals[0].missing).toEqual([])
  })
})

const homeMeal = (recipes: string[], missing: string[] = [], title = 'Repas'): HomeMeal => ({ id: 'm', title, guests: 2, recipes, missing })

describe('meal lines', () => {
  test('mealHeadline is the first recipe, the title without recipe', () => {
    expect(mealHeadline(homeMeal(['Pâtes', 'Salade'], [], 'Fête'))).toBe('Pâtes')
    expect(mealHeadline(homeMeal([], [], 'Restaurant'))).toBe('Restaurant')
  })

  test('mealRecipesLine lists the recipes from two on, null for one or none', () => {
    expect(mealRecipesLine(homeMeal(['Pâtes', 'Salade']))).toBe('Pâtes · Salade')
    expect(mealRecipesLine(homeMeal(['Pâtes']))).toBeNull()
    expect(mealRecipesLine(homeMeal([]))).toBeNull()
  })

  test('missingLabel lowercases each product, null when nothing lacks', () => {
    expect(missingLabel(homeMeal([], ['Parmesan', 'Basilic']))).toBe('manque : parmesan, basilic')
    expect(missingLabel(homeMeal([]))).toBeNull()
  })
})
