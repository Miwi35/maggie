import { describe, test, expect } from 'vitest'
import { formatRate, formatMonth, formatRemaining, basisPointsInput } from './loans'

describe('formatRate', () => {
  test('renders basis points as a percentage', () => {
    expect(formatRate(350)).toBe('3,50 %')
    expect(formatRate(0)).toBe('0,00 %')
    expect(formatRate()).toBe('0,00 %')
  })
})

describe('basisPointsInput', () => {
  test('round-trips a rate typed as a percentage', () => {
    expect(basisPointsInput.parse(3.5)).toBe(350)
    expect(basisPointsInput.format(350)).toBe(3.5)
  })
})

describe('formatMonth', () => {
  test('names the month the API returns', () => {
    expect(formatMonth('2027-03')).toBe('Mars 2027')
  })

  test('falls back rather than breaking', () => {
    expect(formatMonth(null)).toBe('—')
    expect(formatMonth('nonsense')).toBe('nonsense')
  })
})

describe('formatRemaining', () => {
  test('counts in months under a year', () => {
    expect(formatRemaining(7)).toBe('7 mois')
  })

  test('counts in years and months beyond', () => {
    expect(formatRemaining(12)).toBe('1 an')
    expect(formatRemaining(30)).toBe('2 ans et 6 mois')
  })

  test('says so when the loan outlives the horizon', () => {
    expect(formatRemaining(null)).toBe("Au-delà de l'horizon")
  })
})
