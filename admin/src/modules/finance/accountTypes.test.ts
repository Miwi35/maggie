import { describe, test, expect } from 'vitest'
import { ACCOUNT_TYPE_CHOICES, ACCOUNT_TYPE_LABELS, formatCents, formatCentsRounded } from './accountTypes'

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
    test.each([
      [274360, '2\u202F743,60\u00A0€'],
      [495000, '4\u202F950,00\u00A0€'],
      [123456789, '1\u202F234\u202F567,89\u00A0€'],
      [5, '0,05\u00A0€'],
      [0, '0,00\u00A0€'],
    ])('writes %i the way the mock-ups do', (cents, expected) => {
      expect(formatCents(cents)).toBe(expected)
    })

    test('takes a real minus sign for a negative amount', () => {
      expect(formatCents(-12000)).toBe('\u2212120,00\u00A0€')
      expect(formatCents(-5)).toBe('\u22120,05\u00A0€')
    })

    test('defaults to EUR', () => {
      expect(formatCents(1000)).toBe(formatCents(1000, 'EUR'))
    })

    test('shows the symbol of the currency, never its code', () => {
      const dollars = formatCents(1200, 'USD')
      expect(dollars.startsWith('12,00\u00A0')).toBe(true)
      expect(dollars).not.toContain('USD')
    })

    test.each(['XXX', 'xxx', '', ' ', null, undefined, 'ZZZZ'])('falls back to the euro sign on %j', (currency) => {
      const text = formatCents(1200, currency as string | null)
      expect(text).toBe('12,00\u00A0€')
      expect(text).not.toContain('XXX')
    })
  })

  describe('formatCentsRounded', () => {
    test('has no decimals and rounds half up', () => {
      expect(formatCentsRounded(948200)).toBe('9\u202F482\u00A0€')
      expect(formatCentsRounded(948250)).toBe('9\u202F483\u00A0€')
      expect(formatCentsRounded(948249)).toBe('9\u202F482\u00A0€')
      expect(formatCentsRounded(7443007)).toBe('74\u202F430\u00A0€')
      expect(formatCentsRounded(-12049)).toBe('\u2212120\u00A0€')
    })

    test('carries no sign when it rounds to zero', () => {
      expect(formatCentsRounded(-49)).toBe('0\u00A0€')
      expect(formatCentsRounded(0)).toBe('0\u00A0€')
    })

    test('never shows XXX', () => {
      expect(formatCentsRounded(1200, 'XXX')).toBe('12\u00A0€')
    })
  })
})
