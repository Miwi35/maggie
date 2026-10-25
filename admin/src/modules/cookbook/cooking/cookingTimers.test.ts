import { describe, expect, test } from 'vitest'
import { awaitedRemaining, clockOf, doneTitle, quitDetail, rungPositions, stepTimer, timerLabel, timersOfRecipe } from './cookingTimers'
import type { CookingTimer, TimerView } from './cookingTimers'

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

  test('a rung or dismissed timer lets go of its step', () => {
    expect(rungPositions([timer('T1', { status: 'done' }), timer('T2', { stepPosition: 3, status: 'dismissed' }), timer('T3', { stepPosition: 4 })])).toEqual([1, 3])
  })

  test('« encore »: the longest rest of the awaited timers that tick, none when none was started', () => {
    const pasta = timer('T1', { stepPosition: 3, remaining: 130 })
    const vegetables = timer('T2', { stepPosition: 4, remaining: 260, status: 'paused' })
    expect(awaitedRemaining([pasta, vegetables], [3, 4])).toBe(260)
    expect(awaitedRemaining([pasta], [4])).toBeNull()
    expect(awaitedRemaining([timer('T3', { stepPosition: 3, status: 'done', remaining: 0 })], [3])).toBeNull()
  })
})

describe('the words of the timers', () => {
  test('quitting tells the ticking timers, singular or plural; none, nothing to ask', () => {
    const pasta: CookingTimer = timer('T1', { label: 'Pâtes' })
    const vegetables: CookingTimer = timer('T2', { label: 'Légumes', status: 'paused' })
    expect(quitDetail([pasta, vegetables])).toBe("2 minuteurs tournent encore (Pâtes, Légumes) : ils s'arrêteront.")
    expect(quitDetail([pasta])).toBe("1 minuteur tourne encore (Pâtes) : il s'arrêtera.")
    expect(quitDetail([timer('T3', { status: 'done' })])).toBeUndefined()
  })

  test('the alert reads « {label} : c’est prêt »', () => {
    expect(doneTitle(timer('T1', { label: 'Pâtes' }))).toBe("Pâtes : c'est prêt")
  })
})
