import { describe, expect, test } from 'vitest'
import { clockOf, lineTimer, stepTimer, timerLabel, timersOfRecipe } from './cookingTimers'
import type { TimerView } from './cookingTimers'

const timer = (id: string, extra: Partial<TimerView> = {}): TimerView => ({
  id,
  label: 'Eau',
  recipe: '/api/recipes/REF',
  stepPosition: 1,
  durationSeconds: 600,
  endsAt: null,
  remainingSeconds: 600,
  remaining: 600,
  status: 'running',
  finishedAt: null,
  ...extra,
})

describe('timerLabel', () => {
  test.each([
    ["Mets l'eau à bouillir.", 'Eau'],
    ["Plonge les pâtes dans l'eau bouillante.", 'Pâtes'],
    ["Fais revenir les légumes dans l'huile d'olive.", 'Légumes'],
    ['Cuire les pâtes', 'Pâtes'],
    ["Mettre l'eau à bouillir", 'Eau'],
    ['Mets l’eau à bouillir.', 'Eau'],
    ['Ajoute de la crème, puis mélange.', 'Crème'],
    ['Fais dorer les petits oignons nouveaux bien frais', 'Petits oignons nouveaux'],
    ['Laisse reposer la pâte jusqu’à ce qu’elle double', 'Pâte'],
    ['Égoutter, mélanger', 'Égoutter'],
    ['Saler et poivrer', 'Saler'],
  ])('« %s » is « %s »', (text, label) => {
    expect(timerLabel(text, 1)).toBe(label)
  })

  test('an empty text is « Étape n »', () => {
    expect(timerLabel('  ', 4)).toBe('Étape 4')
    expect(timerLabel('...', 2)).toBe('Étape 2')
  })
})

describe('the clock', () => {
  test('mm:ss, and h:mm:ss from an hour on', () => {
    expect(clockOf(552)).toBe('09:12')
    expect(clockOf(0)).toBe('00:00')
    expect(clockOf(3900)).toBe('1:05:00')
  })
})

describe('the timers of a step', () => {
  test('a step’s timer is the last one started for it, a dismissed one only when nothing else is left', () => {
    const old = timer('T1', { status: 'dismissed' })
    const fresh = timer('T2')
    expect(stepTimer([old, fresh], 1)).toBe(fresh)
    expect(stepTimer([old], 1)).toBe(old)
    expect(stepTimer([fresh], 2)).toBeUndefined()
  })

  test('the timers of the recipe being cooked only', () => {
    const other = timer('T2', { recipe: '/api/recipes/SALAD' })
    expect(timersOfRecipe([timer('T1'), other], 'REF').map((t) => t.id)).toEqual(['T1'])
  })

  test('a line shows a running, paused or rung timer, and nothing once it was acknowledged', () => {
    const running = timer('T1')
    const rung = timer('T2', { stepPosition: 2, status: 'done', remaining: 0 })

    expect(lineTimer([running, rung], 1)).toBe(running)
    expect(lineTimer([running, rung], 2)).toBe(rung)
    expect(lineTimer([timer('T3', { status: 'dismissed' })], 1)).toBeUndefined()
    expect(lineTimer([running], 4)).toBeUndefined()
  })
})
