import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useDebtTimeline } from './useDebtTimeline'

const TIMELINE = {
  horizonMonths: 60,
  totalPrincipalRemainingCents: 360000,
  totalMonthlyPaymentCents: 60000,
  totalInterestOverHorizonCents: 0,
  loans: [],
  reliefByMonth: [
    { month: '2026-12', freedCents: 40000, cumulativeFreedCents: 40000, loans: ['Prêt étudiant'] },
  ],
  savingCapacity: {
    monthlyNetIncomeCents: 350000,
    loanPaymentsCents: 60000,
    estimatedLifestyleCents: 0,
    netCapacityCents: 290000,
    isIncomeKnown: true,
  },
}

describe('useDebtTimeline', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('loads the timeline over the default horizon', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(TIMELINE) }),
    )

    const { result } = renderHook(() => useDebtTimeline())

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.timeline).toEqual(TIMELINE)
    expect(fetch).toHaveBeenCalledWith('/api/finance/debt-timeline?months=60', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' },
    })
  })

  test('honours a narrower horizon', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(TIMELINE) }),
    )

    const { result } = renderHook(() => useDebtTimeline(12))

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(fetch).toHaveBeenCalledWith('/api/finance/debt-timeline?months=12', expect.anything())
  })

  test('leaves the timeline empty when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

    const { result } = renderHook(() => useDebtTimeline())

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.timeline).toBeNull()
  })
})
