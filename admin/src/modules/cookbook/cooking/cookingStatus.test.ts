import { readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { describe, expect, test } from 'vitest'
import { resumeDetail, resumeOf, resumesMeal, restOf } from './cookingStatus'
import type { CookingStatus } from './cookingStatusApi'
import { sessionFromState, stateOf } from './cookingSession'
import type { CookingSession } from './cookingSession'
import type { CookingTimer } from './cookingTimers'

const NOW = Date.parse('2026-10-25T12:00:00Z')

const session = (over: Partial<CookingSession> = {}): CookingSession => ({
  origin: { kind: 'meal', id: 'M1' },
  queue: [
    { recipeId: 'A', servings: 4 },
    { recipeId: 'B', servings: 4 },
  ],
  recipeIndex: 0,
  step: 7,
  guests: 4,
  phase: 'cooking',
  doneSteps: { A: [1, 2] },
  startedAt: NOW,
  finishedAt: 0,
  ...over,
})

const timer = (over: Partial<CookingTimer> = {}): CookingTimer => ({
  id: 'T1',
  label: 'Mijotage',
  recipe: '/api/recipes/A',
  stepPosition: 7,
  durationSeconds: 7200,
  endsAt: new Date(NOW + 6420_000).toISOString(),
  remainingSeconds: 6420,
  status: 'running',
  finishedAt: null,
  ...over,
})

const statusOf = (s: CookingSession): CookingStatus => ({ origin: s.origin, state: stateOf(s), updatedAt: '2026-10-25T12:00:00+00:00' })

describe('restOf', () => {
  test.each([
    [6420, '1 h 47'],
    [3600, '1 h 00'],
    [720, '12 min'],
    [59, '59 s'],
    [-4, '0 s'],
  ])('%i seconds read « %s »', (seconds, text) => {
    expect(restOf(seconds)).toBe(text)
  })
})

describe('resumeDetail', () => {
  test('says the step out of the recipe and the timer that runs on it', () => {
    expect(resumeDetail(session(), 'A', 11, [timer()], NOW)).toBe('étape 7/11 · mijotage 1 h 47')
  })

  test('the timer of the current step comes first, else the one that rings next', () => {
    const other = timer({ id: 'T2', label: 'Eau', stepPosition: 2, endsAt: new Date(NOW + 60_000).toISOString(), remainingSeconds: 60 })

    expect(resumeDetail(session(), 'A', 11, [other, timer()], NOW)).toBe('étape 7/11 · mijotage 1 h 47')
    expect(resumeDetail(session({ step: 5 }), 'A', 11, [timer(), other], NOW)).toBe('étape 5/11 · eau 1 min')
  })

  test('a paused timer shows what it had left; a rung or other recipe\'s timer is not mentioned', () => {
    expect(resumeDetail(session(), 'A', 11, [timer({ status: 'paused', endsAt: null, remainingSeconds: 300 })], NOW)).toBe('étape 7/11 · mijotage 5 min')
    expect(resumeDetail(session(), 'A', 11, [timer({ status: 'done' }), timer({ id: 'T9', recipe: '/api/recipes/B' })], NOW)).toBe('étape 7/11')
  })

  test('the preparation is named, and the recipe not being cooked has no detail', () => {
    expect(resumeDetail(session({ step: 0 }), 'A', 11, [], NOW)).toBe('préparation')
    expect(resumeDetail(session(), 'B', 4, [], NOW)).toBeUndefined()
  })

  test('nothing to say while « Pour combien ? » is asked', () => {
    expect(resumeDetail(session({ phase: 'servings', step: 0 }), 'A', 11, [], NOW)).toBeUndefined()
  })
})

describe('resumeOf', () => {
  test('opens the launch the activity came from when it cooks the recipe', () => {
    expect(resumeOf(statusOf(session()), 'B', 4, [], NOW)).toEqual({ to: '/activites/cuisine?meal=M1', detail: undefined })
    expect(resumeOf(statusOf(session({ origin: { kind: 'recipe', id: 'A' }, queue: [{ recipeId: 'A', servings: 2 }] })), 'A', 11, [], NOW)).toEqual({
      to: '/activites/cuisine?recipe=A',
      detail: 'étape 7/11',
    })
  })

  test('nothing when there is no activity, or it does not cook the recipe, or its state is unreadable', () => {
    expect(resumeOf(null, 'A', 11, [], NOW)).toBeNull()
    expect(resumeOf(statusOf(session()), 'Z', 11, [], NOW)).toBeNull()
    expect(resumeOf({ origin: { kind: 'meal', id: 'M1' }, state: { nope: true }, updatedAt: '' }, 'A', 11, [], NOW)).toBeNull()
  })
})

describe('resumesMeal', () => {
  test('is true only for the meal the activity was launched from', () => {
    expect(resumesMeal(statusOf(session()), 'M1')).toBe(true)
    expect(resumesMeal(statusOf(session()), 'M2')).toBe(false)
    expect(resumesMeal(statusOf(session({ origin: { kind: 'recipe', id: 'M1' } })), 'M1')).toBe(false)
    expect(resumesMeal(null, 'M1')).toBe(false)
  })
})

/** Walks up to `api/contract/`, wherever the suite runs (the checkout, or `task wt:test:admin`). */
const contractFile = (name: string): string => {
  let candidate = resolve(process.cwd())
  for (;;) {
    const file = join(candidate, 'api', 'contract', name)
    try {
      statSync(file)

      return file
    } catch {
      const parent = dirname(candidate)
      if (parent === candidate) throw new Error(`No api/contract/${name} above ${process.cwd()}: UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract`)
      candidate = parent
    }
  }
}

describe('the activity-status contract', () => {
  test('the recorded response is read as a resumable session', () => {
    const recorded = JSON.parse(readFileSync(contractFile(join('responses', 'activity_status.collection.json')), 'utf8')) as { member: CookingStatus[] }
    const [item] = recorded.member

    expect(item.origin).toEqual({ kind: expect.stringMatching(/^(meal|recipe)$/), id: expect.any(String) })
    const resumed = sessionFromState(item.origin, item.state)
    expect(resumed).toMatchObject({ phase: 'cooking', step: 2, guests: 4, recipeIndex: 0 })
    expect(resumed?.doneSteps).toEqual({ '01JB0000000000000000000001': [1] })
    expect(resumed?.startedAt).toBe(1785000000000)
  })

  test('what the client writes is what it reads back', () => {
    const written = session()

    expect(sessionFromState(written.origin, JSON.parse(JSON.stringify(stateOf(written))))).toEqual(written)
  })
})
