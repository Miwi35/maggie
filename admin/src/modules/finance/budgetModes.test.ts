import { describe, test, expect } from 'vitest'
import {
  BUDGET_MODE_CHOICES,
  BUDGET_MODE_LABELS,
  MONTH_CHOICES,
  formatPeriod,
  consumedPercent,
  toEnvelopePayload,
  formatSeason,
  seasonMonths,
  overlappingMonth,
  seasonFor,
} from './budgetModes'

describe('budgetModes', () => {
  test('every mode choice has a matching label', () => {
    for (const choice of BUDGET_MODE_CHOICES) {
      expect(BUDGET_MODE_LABELS[choice.id]).toBe(choice.name)
    }
  })

  test('covers the three API budget modes', () => {
    expect(BUDGET_MODE_CHOICES.map((c) => c.id)).toEqual(['monthly', 'annual', 'one_off'])
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

  test('calls a one-off envelope by what it is, having no period', () => {
    expect(formatPeriod('one_off', null, null)).toBe('Ponctuelle')
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

describe('toEnvelopePayload', () => {
  test('drops the month of an annual envelope', () => {
    expect(toEnvelopePayload({ mode: 'annual', month: 7, amountCents: 100 })).toEqual({
      mode: 'annual',
      month: null,
      amountCents: 100,
      seasonalAmounts: [],
    })
  })

  test('leaves a monthly envelope untouched', () => {
    const data = { mode: 'monthly', month: 7 }
    expect(toEnvelopePayload(data)).toEqual(data)
  })

  test('sends a one-off envelope without category nor period', () => {
    expect(
      toEnvelopePayload({ mode: 'one_off', name: 'Hellfest 2026', category: '/api/categories/1', year: 2026, month: 10, amountCents: 40000 }),
    ).toEqual({ mode: 'one_off', name: 'Hellfest 2026', category: null, year: null, month: null, amountCents: 40000, seasonalAmounts: [] })
  })

  test('drops a name typed before switching to a monthly envelope', () => {
    expect(toEnvelopePayload({ mode: 'monthly', name: 'Hellfest', month: 7 })).toEqual({ mode: 'monthly', month: 7 })
  })
})

describe('seasons', () => {
  test('a season may cross the new year', () => {
    expect(seasonMonths({ fromMonth: 11, toMonth: 2 })).toEqual([11, 12, 1, 2])
  })

  test('finds the season budgeting a month', () => {
    const winter = { fromMonth: 11, toMonth: 2, amountCents: 4500 }
    expect(seasonFor([winter], 1)).toEqual(winter)
    expect(seasonFor([winter], 3)).toBeNull()
    expect(seasonFor([winter], null)).toBeNull()
  })

  test('names a season by its months', () => {
    expect(formatSeason({ fromMonth: 11, toMonth: 2 })).toBe('saison nov.–févr.')
    expect(formatSeason({ fromMonth: 7, toMonth: 7 })).toBe('saison juil.')
  })

  test('finds the month two seasons share', () => {
    expect(
      overlappingMonth([
        { fromMonth: 11, toMonth: 2, amountCents: 4500 },
        { fromMonth: 2, toMonth: 3, amountCents: 3000 },
      ]),
    ).toBe(2)
    expect(
      overlappingMonth([
        { fromMonth: 11, toMonth: 2, amountCents: 4500 },
        { fromMonth: 7, toMonth: 8, amountCents: 1000 },
      ]),
    ).toBeNull()
  })

  test('ignores a season still being filled in', () => {
    expect(overlappingMonth([{ fromMonth: 11, toMonth: 2 }, { fromMonth: 12 }, null])).toBeNull()
  })
})
