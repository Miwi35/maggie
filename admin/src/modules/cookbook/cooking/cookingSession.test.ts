import { beforeEach, describe, expect, test } from 'vitest'
import {
  advance,
  back,
  clearSession,
  completeStep,
  confirmServings,
  goToStep,
  isStepDone,
  loadSession,
  mealLaunchPath,
  mealSession,
  recipeLaunchPath,
  progressOf,
  restartSession,
  sessionFromState,
  stateOf,
  launchPathOf,
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

  test('« Suivant » ticks the step it leaves, in order and once', () => {
    let session = mealSession('M1', ['A'], 2, 1000)
    session = advance(session, 3)
    expect(session.doneSteps).toEqual({})

    session = advance(advance(session, 3), 3)

    expect(session).toMatchObject({ step: 3, doneSteps: { A: [1, 2] } })
    expect(completeStep(session).doneSteps).toEqual({ A: [1, 2, 3] })
    expect(completeStep(completeStep(session))).toEqual(completeStep(session))
  })

  test('the last step is ticked as the recipe is left, and ticks stay with their recipe', () => {
    const last = { ...mealSession('M1', ['A', 'B'], 2, 1000), step: 2 }
    const next = advance(last, 2)

    expect(next).toMatchObject({ recipeIndex: 1, step: 0, doneSteps: { A: [2] } })
    expect(isStepDone(next, 'A', 2)).toBe(true)
    expect(isStepDone(next, 'B', 2)).toBe(false)
  })

  test('« Lancer et continuer » moves on without ticking the step', () => {
    const session = { ...mealSession('M1', ['A'], 2, 1000), step: 2 }

    expect(advance(session, 3, 5000, false)).toMatchObject({ step: 3, doneSteps: {} })
  })

  test('the preparation has nothing to tick', () => {
    const session = mealSession('M1', ['A'], 2, 1000)

    expect(completeStep(session)).toBe(session)
  })

  test('a step touched in the column is shown without being ticked, in any recipe of the queue', () => {
    const session = { ...mealSession('M1', ['A', 'B'], 2, 1000), step: 1 }

    expect(goToStep(session, 0, 5)).toMatchObject({ recipeIndex: 0, step: 5, doneSteps: {} })
    expect(goToStep(session, 1, 3)).toMatchObject({ recipeIndex: 1, step: 3, doneSteps: {} })
    expect(goToStep(session, 2, 1)).toBe(session)
    expect(goToStep(session, 0, 0)).toBe(session)
    expect(goToStep(recipeSession('R1', 2), 0, 1)).toMatchObject({ phase: 'servings', step: 0 })
  })

  test('the progress counts the ticked steps of every recipe over all their steps', () => {
    const session = { ...mealSession('M1', ['A', 'B'], 2, 1000), doneSteps: { A: [1, 2, 9], B: [1] } }

    expect(progressOf(session, { A: 6, B: 5 })).toEqual({ done: 3, total: 11 })
    expect(progressOf(session, { A: 6 })).toEqual({ done: 2, total: 6 })
  })

  test('the ticked steps survive a reload', () => {
    const session = advance(advance({ ...mealSession('M1', ['A'], 2, 1000), step: 1 }, 4), 4)
    saveSession(session)

    expect(loadSession({ kind: 'meal', id: 'M1' })?.doneSteps).toEqual({ A: [1, 2] })
  })

  test('serving the last recipe notes when, so « Bon appétit » can say how long it took', () => {
    const last = { ...mealSession('M1', ['A'], 2, 1000), step: 1 }

    expect(advance(last, 1, 61000)).toMatchObject({ phase: 'done', startedAt: 1000, finishedAt: 61000 })
    expect(skipRecipe({ ...last, step: 0 }, 9000)).toMatchObject({ phase: 'done', finishedAt: 9000 })
  })

  test('a session stored before the column is resumed with nothing ticked', () => {
    const old: Record<string, unknown> = { ...mealSession('M1', ['A'], 2), ready: [1] }
    delete old.doneSteps
    delete old.startedAt
    delete old.finishedAt
    sessionStorage.setItem('maggie.cooking-session', JSON.stringify(old))

    expect(loadSession({ kind: 'meal', id: 'M1' })).toMatchObject({ doneSteps: {}, finishedAt: 0 })
  })

  describe('the status of the activity (MAG-615)', () => {
    const origin = { kind: 'meal', id: 'M1' } as const
    const started = (): ReturnType<typeof mealSession> => advance(advance(mealSession('M1', ['A', 'B'], 4, 1000), 3, 2000), 3, 2000)

    test('launchPathOf gives back the address of the launch', () => {
      expect(launchPathOf({ kind: 'meal', id: 'M1' })).toBe('/activites/cuisine?meal=M1')
      expect(launchPathOf({ kind: 'recipe', id: 'R 1' })).toBe('/activites/cuisine?recipe=R%201')
    })

    test('the state keeps everything but the origin and the end', () => {
      const session = { ...mealSession('M1', ['A', 'B'], 4, 1000), step: 2, doneSteps: { A: [1] } }

      expect(stateOf(session)).toEqual({
        queue: [
          { recipeId: 'A', servings: 4 },
          { recipeId: 'B', servings: 4 },
        ],
        recipeIndex: 0,
        step: 2,
        doneSteps: { A: [1] },
        guests: 4,
        phase: 'cooking',
        startedAt: 1000,
      })
    })

    test('a session comes back from its state, whichever device wrote it', () => {
      const session = started()

      expect(sessionFromState(origin, JSON.parse(JSON.stringify(stateOf(session))))).toEqual(session)
    })

    test.each([
      ['nothing', null],
      ['no queue', { recipeIndex: 0, phase: 'cooking' }],
      ['an empty queue', { queue: [], recipeIndex: 0, phase: 'cooking' }],
      ['a finished activity', { queue: [{ recipeId: 'A', servings: 2 }], recipeIndex: 0, phase: 'done' }],
      ['a recipe index outside the queue', { queue: [{ recipeId: 'A', servings: 2 }], recipeIndex: 3, phase: 'cooking' }],
      ['a queue entry without a recipe', { queue: [{ servings: 2 }], recipeIndex: 0, phase: 'cooking' }],
    ])('%s cannot be resumed', (_name, raw) => {
      expect(sessionFromState(origin, raw)).toBeNull()
    })

    test('fields that are off fall back instead of breaking the resume', () => {
      const resumed = sessionFromState(origin, { queue: [{ recipeId: 'A', servings: 3 }], recipeIndex: 0, phase: 'servings', step: -2, guests: 'x', doneSteps: [1] })

      expect(resumed).toMatchObject({ step: 0, guests: 3, doneSteps: {}, phase: 'servings', finishedAt: 0 })
    })

    test('« Recommencer » keeps recipes and servings, drops the steps ticked and goes back to the preparation', () => {
      const session = { ...started(), recipeIndex: 1, step: 3, guests: 6 }

      const restarted = restartSession(session, 5000)

      expect(restarted).toMatchObject({ recipeIndex: 0, step: 0, phase: 'cooking', doneSteps: {}, startedAt: 5000, finishedAt: 0, guests: 6, queue: session.queue })
    })
  })
})
