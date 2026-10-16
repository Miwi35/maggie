import { describe, expect, test } from 'vitest'
import { moveStep, removeStep, setDuration, stepOfError, stepsPayload } from './recipeSteps'
import type { RecipeStepDraft } from './recipeSteps'

const step = (text: string, waitsFor: number[] = [], extra: Partial<RecipeStepDraft> = {}): RecipeStepDraft => ({
  id: text,
  text,
  durationMinutes: null,
  background: false,
  ingredients: [],
  utensils: [],
  waitsFor,
  ...extra,
})

// The project's reference example: 3 waits for 1, 5 waits for 3 and 4.
const example = () => [
  step('eau', [], { durationMinutes: 10, background: true }),
  step('couper'),
  step('pâtes', [1], { durationMinutes: 12, background: true }),
  step('revenir', [], { durationMinutes: 8 }),
  step('mélanger', [3, 4]),
]

const order = (steps: RecipeStepDraft[]) => steps.map((s) => s.text)

describe('moveStep', () => {
  test('dependencies follow their steps when another one is moved', () => {
    const moved = moveStep(example(), 1, 3) // « couper » goes after « revenir »
    expect(order(moved)).toEqual(['eau', 'pâtes', 'revenir', 'couper', 'mélanger'])
    expect(moved.map((s) => s.waitsFor)).toEqual([[], [1], [], [], [2, 3]])
  })

  test('a dependency that became later is removed', () => {
    const moved = moveStep(example(), 0, 2) // « eau » goes after « pâtes »
    expect(order(moved)).toEqual(['couper', 'pâtes', 'eau', 'revenir', 'mélanger'])
    // « pâtes » (now 2) no longer waits for « eau » (now 3); « mélanger » still waits for pâtes and revenir.
    expect(moved.map((s) => s.waitsFor)).toEqual([[], [], [], [], [2, 4]])
  })

  test('moving a step onto itself changes nothing', () => {
    const steps = example()
    expect(moveStep(steps, 2, 2)).toBe(steps)
  })

  test('moving a step up keeps the dependencies that stay earlier', () => {
    const moved = moveStep(example(), 4, 0)
    expect(order(moved)).toEqual(['mélanger', 'eau', 'couper', 'pâtes', 'revenir'])
    expect(moved.map((s) => s.waitsFor)).toEqual([[], [], [], [2], []])
  })
})

describe('removeStep', () => {
  test('nobody waits for a removed step and the later positions shift', () => {
    const removed = removeStep(example(), 0)
    expect(order(removed)).toEqual(['couper', 'pâtes', 'revenir', 'mélanger'])
    expect(removed.map((s) => s.waitsFor)).toEqual([[], [], [], [2, 3]])
  })
})

describe('setDuration', () => {
  test('clearing the duration puts the step back in the foreground', () => {
    const cleared = setDuration(step('eau', [], { durationMinutes: 10, background: true }), null)
    expect(cleared).toMatchObject({ durationMinutes: null, background: false })
  })

  test('a duration keeps the background flag', () => {
    expect(setDuration(step('eau', [], { durationMinutes: 10, background: true }), 20)).toMatchObject({ durationMinutes: 20, background: true })
  })
})

describe('stepsPayload', () => {
  test('sends the whole list in order, ingredients and utensils by name', () => {
    const payload = stepsPayload([
      step('  Cuire  ', [], {
        durationMinutes: 12,
        background: true,
        ingredients: [{ id: '01I', name: 'Pâtes' }, 'Sel'],
        utensils: [{ id: '01U', name: 'Casserole' }, 'Passoire'],
      }),
      step('Égoutter', [1]),
    ])
    expect(payload).toEqual([
      { text: 'Cuire', durationMinutes: 12, background: true, ingredientIds: ['Pâtes', 'Sel'], utensils: ['Casserole', 'Passoire'], waitsFor: [] },
      { text: 'Égoutter', durationMinutes: null, background: false, ingredientIds: [], utensils: [], waitsFor: [1] },
    ])
  })

  test('never sends « en fond » without a duration', () => {
    expect(stepsPayload([step('x', [], { background: true })])[0].background).toBe(false)
  })

  test('a recipe without steps sends an empty list', () => {
    expect(stepsPayload(undefined)).toEqual([])
  })
})

describe('stepOfError', () => {
  test.each([
    ['Step 3 needs a text.', 3],
    ['Step 2: a step runs in the background only with a duration.', 2],
    ['"Pâtes" cannot leave the recipe: step 4 (« Cuire ») uses it. Change the step first.', 4],
    ['steps must be a list.', null],
  ])('%s', (message, expected) => {
    expect(stepOfError(message)).toBe(expected)
  })
})
