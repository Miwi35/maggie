import { describe, test, expect } from 'vitest'
import {
  EXPENSE_STATUS_CHOICES,
  INCOME_STATUS_CHOICES,
  natureOfAmount,
  signedAmountCents,
  statusLabel,
} from './transactionStatuses'

describe('transactionStatuses', () => {
  test('an expense offers the four API statuses', () => {
    expect(EXPENSE_STATUS_CHOICES.map((c) => c.id)).toEqual([
      'spent',
      'committed',
      'planned',
      'to_arbitrate',
    ])
  })

  test('an income offers three, without "committed", and never says spent or committed', () => {
    expect(INCOME_STATUS_CHOICES.map((c) => c.id)).toEqual(['spent', 'planned', 'to_arbitrate'])
    expect(INCOME_STATUS_CHOICES.map((c) => c.name)).toEqual(['Reçue', 'Attendue', 'À arbitrer'])
  })

  test('the nature is the sign of the amount, and zero reads as an expense', () => {
    expect(natureOfAmount(-1)).toBe('expense')
    expect(natureOfAmount(1)).toBe('income')
    expect(natureOfAmount(0)).toBe('expense')
    expect(natureOfAmount(undefined)).toBe('expense')
  })

  test('the sign is applied to whatever was typed', () => {
    expect(signedAmountCents(1250, 'expense')).toBe(-1250)
    expect(signedAmountCents(1250, 'income')).toBe(1250)
    expect(signedAmountCents(-1250, 'income')).toBe(1250)
    expect(signedAmountCents(-1250, 'expense')).toBe(-1250)
  })

  test('the same status reads differently on an expense and on an income', () => {
    expect(statusLabel('spent', -500)).toBe('Dépensée')
    expect(statusLabel('spent', 500)).toBe('Reçue')
    expect(statusLabel('planned', -500)).toBe('Planifiée')
    expect(statusLabel('planned', 500)).toBe('Attendue')
    expect(statusLabel('committed', -500)).toBe('Engagée')
    expect(statusLabel('to_arbitrate', 500)).toBe('À arbitrer')
  })

  test('a committed income from before the rule reads as expected, never as engaged', () => {
    expect(statusLabel('committed', 500)).toBe('Attendue')
  })

  test('an unknown status is shown as is', () => {
    expect(statusLabel('weird', -1)).toBe('weird')
  })
})
