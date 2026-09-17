import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useMonthlyReview, VERDICT_LABELS } from './useMonthlyReview'

const REVIEW = {
  year: 2026,
  month: 8,
  reviewableCents: 20000,
  keptCents: 12000,
  avoidableCents: 3000,
  unratedCents: 5000,
  ratedCount: 2,
  pendingCount: 1,
  optimisationScore: 80,
  isComplete: false,
  pending: [
    {
      id: 'tx-1',
      label: 'Achat divers',
      amountCents: -5000,
      currency: 'EUR',
      bookedAt: '2026-08-20',
      categoryId: null,
      categoryName: null,
      retrospect: 'unrated',
    },
  ],
  comparison: {
    thisMonthCents: 110000,
    previousMonthCents: 60000,
    recentAverageCents: 20000,
    sameMonthLastYearCents: 150000,
  },
}

describe('useMonthlyReview', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('loads the review of the requested month', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(REVIEW) }),
    )

    const { result } = renderHook(() => useMonthlyReview(2026, 8))

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.review).toEqual(REVIEW)
    expect(fetch).toHaveBeenCalledWith('/api/finance/monthly-review?year=2026&month=8', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' },
    })
  })

  test('rating a spend patches the transaction and reloads the review', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(REVIEW) })
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({}) })
      .mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({ ...REVIEW, pendingCount: 0, pending: [] }),
      })
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useMonthlyReview(2026, 8))
    await waitFor(() => expect(result.current.loading).toBe(false))

    let ok
    await act(async () => {
      ok = await result.current.rate('tx-1', 'avoidable')
    })

    expect(ok).toBe(true)
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/transactions/tx-1', {
      method: 'PATCH',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer test-jwt-token',
        'Content-Type': 'application/merge-patch+json',
      },
      body: JSON.stringify({ retrospect: 'avoidable' }),
    })
    await waitFor(() => expect(result.current.review?.pendingCount).toBe(0))
  })

  test('a refused verdict is reported without touching the review', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(REVIEW) })
        .mockResolvedValueOnce({ ok: false }),
    )

    const { result } = renderHook(() => useMonthlyReview(2026, 8))
    await waitFor(() => expect(result.current.loading).toBe(false))

    let ok
    await act(async () => {
      ok = await result.current.rate('tx-1', 'keep')
    })

    expect(ok).toBe(false)
    expect(result.current.review?.pendingCount).toBe(1)
  })
})

describe('VERDICT_LABELS', () => {
  test('phrases the verdicts the way the doc does', () => {
    expect(VERDICT_LABELS.keep).toBe('À conserver')
    expect(VERDICT_LABELS.avoidable).toBe("J'aurais pu m'en passer")
    expect(VERDICT_LABELS.unrated).toBe('À revoir')
  })
})
