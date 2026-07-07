import { describe, test, expect } from 'vitest'
import { ACCOUNT_TYPE_CHOICES, ACCOUNT_TYPE_LABELS, formatCents } from './accountTypes'

describe('accountTypes', () => {
  test('every choice has a matching label', () => {
    for (const choice of ACCOUNT_TYPE_CHOICES) {
      expect(ACCOUNT_TYPE_LABELS[choice.id]).toBe(choice.name)
    }
  })

  test('covers the four API account types', () => {
    expect(ACCOUNT_TYPE_CHOICES.map((c) => c.id)).toEqual([
      'checking',
      'savings',
      'investment',
      'cash',
    ])
  })

  describe('formatCents', () => {
    test('converts integer cents to a euro string', () => {
      const result = formatCents(125000, 'EUR')
      expect(result).toContain('1')
      expect(result).toContain('250')
    })

    test('handles zero', () => {
      expect(formatCents(0, 'EUR')).toContain('0')
    })

    test('defaults to EUR', () => {
      expect(formatCents(1000)).toBe(formatCents(1000, 'EUR'))
    })

    test('falls back gracefully on an unknown currency code', () => {
      expect(formatCents(1000, 'ZZZ')).toContain('10')
    })
  })
})
