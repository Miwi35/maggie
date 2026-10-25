import { describe, expect, test } from 'vitest'
import type { RecipeLine, RecipeStep } from '../recipeSheet'
import { awaitedName, pendingWaits, stepIngredients, waitLabel } from './cookingSteps'

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

/** The reference example of the Étapes project: boil 10 min (background), cut, pasta 12 min (background, waits 1), sauté 8 min, drain (waits 3 and 4). */
const REFERENCE = [
  step(1, "Mettre l'eau à bouillir", { durationMinutes: 10, background: true }),
  step(2, 'Couper les légumes'),
  step(3, 'Cuire les pâtes', { durationMinutes: 12, background: true, waitsFor: [1] }),
  step(4, 'Faire revenir les légumes', { durationMinutes: 8 }),
  step(5, 'Égoutter, mélanger', { waitsFor: [3, 4] }),
]

describe('pendingWaits', () => {
  test('step 3 waits for the water, step 5 for the pasta and the vegetables', () => {
    expect(pendingWaits(REFERENCE, 3, [])).toEqual([1])
    expect(pendingWaits(REFERENCE, 5, [])).toEqual([3, 4])
  })

  test('a step that waits for nothing is never held', () => {
    expect(pendingWaits(REFERENCE, 1, [])).toEqual([])
    expect(pendingWaits(REFERENCE, 2, [])).toEqual([])
    expect(pendingWaits(REFERENCE, 4, [])).toEqual([])
  })

  test('« C\'est prêt » lifts the steps it forced, and only those', () => {
    expect(pendingWaits(REFERENCE, 3, [1])).toEqual([])
    expect(pendingWaits(REFERENCE, 5, [3])).toEqual([4])
    expect(pendingWaits(REFERENCE, 5, [3, 4])).toEqual([])
  })

  test('an awaited step without a duration is finished once passed', () => {
    const steps = [step(1, 'Éplucher'), step(2, 'Cuire', { waitsFor: [1] })]

    expect(pendingWaits(steps, 2, [])).toEqual([])
  })

  test('a position that is not in the recipe holds nothing', () => {
    expect(pendingWaits([step(1, 'Cuire', { waitsFor: [7] })], 1, [])).toEqual([])
    expect(pendingWaits(REFERENCE, 9, [])).toEqual([])
  })
})

describe('waitLabel', () => {
  test('names what is awaited and the steps', () => {
    expect(waitLabel(REFERENCE, [3, 4])).toBe('Attend les pâtes et les légumes · étapes 3 et 4')
    expect(waitLabel(REFERENCE, [1])).toBe("Attend l'eau à bouillir · étape 1")
  })

  test('three steps read « a, b et c »', () => {
    const steps = [step(1, 'Cuire le riz', { durationMinutes: 5 }), step(2, 'Cuire la viande', { durationMinutes: 5 }), step(3, 'Cuire un oeuf', { durationMinutes: 5 })]

    expect(waitLabel(steps, [1, 2, 3])).toBe('Attend le riz, la viande et un oeuf · étapes 1, 2 et 3')
  })
})

describe('awaitedName', () => {
  test('starts at the first article', () => {
    expect(awaitedName('Faire revenir les légumes')).toBe('les légumes')
    expect(awaitedName('Cuire les pâtes')).toBe('les pâtes')
  })

  test('without an article the whole text is the name, in lower case', () => {
    expect(awaitedName('Égoutter, mélanger')).toBe('égoutter, mélanger')
  })

  test('a long text is cut to a few words', () => {
    expect(awaitedName('Laisser reposer la pâte sous un linge humide dans un endroit chaud')).toBe('la pâte sous un linge')
  })
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
