import { describe, test, expect } from 'vitest'
import { OBLIGATION_CHOICES, OBLIGATION_LABELS } from './obligationFlags'

describe('obligationFlags', () => {
  test('every choice has a matching label', () => {
    for (const choice of OBLIGATION_CHOICES) {
      expect(OBLIGATION_LABELS[choice.id]).toBe(choice.name)
    }
  })

  test('covers every API obligation flag, income included', () => {
    expect(OBLIGATION_CHOICES.map((c) => c.id)).toEqual([
      'mandatory',
      'optional',
      'saving',
      'investment',
      'income',
    ])
  })
})
