import { beforeEach, describe, expect, test } from 'vitest'
import {
  advance,
  back,
  clearSession,
  confirmServings,
  loadSession,
  mealLaunchPath,
  mealSession,
  recipeLaunchPath,
  recipeSession,
  saveSession,
  skipRecipe,
  withGuests,
} from './cookingSession'

describe('cookingSession', () => {
  beforeEach(() => sessionStorage.clear())

  test('the launch addresses', () => {
    expect(mealLaunchPath('M1')).toBe('/activites/cuisine?meal=M1')
    expect(recipeLaunchPath('R1')).toBe('/activites/cuisine?recipe=R1')
  })

  test('a meal goes straight to the preparation, every recipe for the meal guests', () => {
    const session = mealSession('M1', ['A', 'B', 'C'], 4)

    expect(session).toMatchObject({ phase: 'cooking', recipeIndex: 0, step: 0, guests: 4 })
    expect(session.queue).toEqual([
      { recipeId: 'A', servings: 4 },
      { recipeId: 'B', servings: 4 },
      { recipeId: 'C', servings: 4 },
    ])
  })

  test('a recipe asks « Pour combien ? » first, filled with its own servings', () => {
    expect(recipeSession('R1', 4)).toMatchObject({ phase: 'servings', guests: 4, queue: [{ recipeId: 'R1', servings: 4 }] })
  })

  test('guests never go under one', () => {
    expect(withGuests(recipeSession('R1', 4), 0).guests).toBe(1)
    expect(withGuests(recipeSession('R1', 4), -3).queue[0].servings).toBe(1)
  })

  test('4 → 2 changes the servings of every recipe of the queue', () => {
    const session = withGuests(mealSession('M1', ['A', 'B'], 4), 2)

    expect(session.guests).toBe(2)
    expect(session.queue.map((entry) => entry.servings)).toEqual([2, 2])
  })

  test('« Continuer » opens the preparation', () => {
    expect(confirmServings(recipeSession('R1', 4))).toMatchObject({ phase: 'cooking', step: 0 })
  })

  test('three recipes follow one another in order, then « Bon appétit »', () => {
    let session = mealSession('M1', ['A', 'B', 'C'], 2)
    const visited: string[] = []

    while (session.phase === 'cooking') {
      visited.push(`${session.queue[session.recipeIndex].recipeId}:${session.step}`)
      session = advance(session, 2)
    }

    expect(visited).toEqual(['A:0', 'A:1', 'A:2', 'B:0', 'B:1', 'B:2', 'C:0', 'C:1', 'C:2'])
    expect(session.phase).toBe('done')
  })

  test('a recipe without a step is passed over', () => {
    const second = skipRecipe(mealSession('M1', ['A', 'B'], 2))

    expect(second).toMatchObject({ recipeIndex: 1, step: 0, phase: 'cooking' })
    expect(skipRecipe(second).phase).toBe('done')
  })

  test('« Précédent » goes back to the preparation and no further', () => {
    const onStepTwo = advance(advance(mealSession('M1', ['A'], 2), 3), 3)

    expect(back(onStepTwo).step).toBe(1)
    expect(back(back(onStepTwo)).step).toBe(0)
    expect(back(back(back(onStepTwo))).step).toBe(0)
  })

  test('survives a reload of the same launch', () => {
    const session = advance(mealSession('M1', ['A', 'B'], 3), 2)
    saveSession(session)

    expect(loadSession({ kind: 'meal', id: 'M1' })).toEqual(session)
  })

  test('does not resume another launch, a finished one or a broken one', () => {
    saveSession(mealSession('M1', ['A'], 3))
    expect(loadSession({ kind: 'meal', id: 'M2' })).toBeNull()
    expect(loadSession({ kind: 'recipe', id: 'M1' })).toBeNull()

    saveSession({ ...mealSession('M1', ['A'], 3), phase: 'done' })
    expect(loadSession({ kind: 'meal', id: 'M1' })).toBeNull()

    sessionStorage.setItem('maggie.cooking-session', '{not json')
    expect(loadSession({ kind: 'meal', id: 'M1' })).toBeNull()

    sessionStorage.setItem('maggie.cooking-session', JSON.stringify({ ...mealSession('M1', ['A'], 3), recipeIndex: 4 }))
    expect(loadSession({ kind: 'meal', id: 'M1' })).toBeNull()
  })

  test('clearSession forgets it', () => {
    saveSession(mealSession('M1', ['A'], 3))
    clearSession()

    expect(loadSession({ kind: 'meal', id: 'M1' })).toBeNull()
  })
})
