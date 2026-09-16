import { describe, test, expect } from 'vitest'
import {
  BUDGET_MODE_CHOICES,
  BUDGET_MODE_LABELS,
  MONTH_CHOICES,
  formatPeriod,
  consumedPercent,
  clearMonthOnAnnual,
} from './budgetModes'

describe('budgetModes', () => {
  test('every mode choice has a matching label', () => {
    for (const choice of BUDGET_MODE_CHOICES) {
      expect(BUDGET_MODE_LABELS[choice.id]).toBe(choice.name)
    }
  })

  test('covers the two API budget modes', () => {
    expect(BUDGET_MODE_CHOICES.map((c) => c.id)).toEqual(['monthly', 'annual'])
  })

  test('offers the twelve months', () => {
    expect(MONTH_CHOICES).toHaveLength(12)
    expect(MONTH_CHOICES[0]).toEqual({ id: 1, name: 'Janvier' })
    expect(MONTH_CHOICES[11]).toEqual({ id: 12, name: 'Décembre' })
  })
})

describe('formatPeriod', () => {
  test('names the month of a monthly envelope', () => {
    expect(formatPeriod('monthly', 2026, 7)).toBe('Juillet 2026')
  })

  test('names the year of an annual envelope', () => {
    expect(formatPeriod('annual', 2026, null)).toBe('Année 2026')
  })

  test('falls back to the year when a monthly envelope has no month', () => {
    expect(formatPeriod('monthly', 2026, null)).toBe('Année 2026')
  })
})

describe('consumedPercent', () => {
  test('reports the spent share of the budget', () => {
    expect(consumedPercent(4599, 40000)).toBe(11)
  })

  test('clamps an overspent budget to 100', () => {
    expect(consumedPercent(50000, 40000)).toBe(100)
  })

  test('treats a zero budget as fully consumed once anything is spent', () => {
    expect(consumedPercent(100, 0)).toBe(100)
    expect(consumedPercent(0, 0)).toBe(0)
  })
})

describe('clearMonthOnAnnual', () => {
  test('drops the month of an annual envelope', () => {
    expect(clearMonthOnAnnual({ mode: 'annual', month: 7, amountCents: 100 })).toEqual({
      mode: 'annual',
      month: null,
      amountCents: 100,
    })
  })

  test('leaves a monthly envelope untouched', () => {
    const data = { mode: 'monthly', month: 7 }
    expect(clearMonthOnAnnual(data)).toEqual(data)
  })
})
