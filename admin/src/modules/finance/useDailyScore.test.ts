import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import {
  useDailyScore,
  reasonText,
  SCORE_LABELS,
  SCORE_SEVERITY,
} from './useDailyScore'

const SCORE = {
  score: 'orange',
  year: 2026,
  month: 9,
  reasons: [{ code: 'optional_category_exceeded', categoryName: 'Loisirs', amountCents: 4500 }],
  budget: {
    totalBudgetedCents: 60000,
    totalConsumedCents: 65000,
    totalPlannedCents: 0,
    totalAvailableCents: -5000,
    overspentCategories: ['Loisirs'],
  },
  cushion: { state: 'complete', blocksGreenScore: false },
  comparison: {
    thisMonthCents: 65000,
    sameMonthLastYearCents: 45000,
    differenceCents: 20000,
    isBetter: false,
  },
}

describe('useDailyScore', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('loads the score of the requested period', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(SCORE) }),
    )

    const { result } = renderHook(() => useDailyScore(2026, 9))

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.score).toEqual(SCORE)
    expect(fetch).toHaveBeenCalledWith('/api/finance/daily-score?year=2026&month=9', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' },
    })
  })

  test('leaves the score empty when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

    const { result } = renderHook(() => useDailyScore(2026, 9))

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.score).toBeNull()
  })
})

describe('score presentation', () => {
  test('every score has a label and a severity', () => {
    for (const score of ['green', 'neutral', 'orange', 'red']) {
      expect(SCORE_LABELS[score]).toBeTruthy()
      expect(SCORE_SEVERITY[score]).toBeTruthy()
    }
  })

  test('a mandatory overspend says it is mandatory', () => {
    expect(
      reasonText({ code: 'mandatory_category_exceeded', categoryName: 'Alimentation', amountCents: 5000 }),
    ).toBe('Alimentation (obligatoire) dépassée de 50,00 €')
  })

  test('plans over budget are phrased as a forecast, not a fact', () => {
    expect(
      reasonText({ code: 'plans_exceed_category_budget', categoryName: 'Voyages', amountCents: 60000 }),
    ).toBe('Les dépenses planifiées feraient dépasser Voyages de 600,00 €')
  })

  test('the year-on-year comparison reads both ways', () => {
    expect(reasonText({ code: 'below_last_year', amountCents: 20000 })).toBe(
      "200,00 € de moins qu'à la même période l'an dernier",
    )
    expect(reasonText({ code: 'above_last_year', amountCents: 20000 })).toBe(
      "200,00 € de plus qu'à la même période l'an dernier",
    )
  })

  test('an unknown code falls back to itself rather than breaking', () => {
    expect(reasonText({ code: 'something_new' })).toBe('something_new')
  })
})
