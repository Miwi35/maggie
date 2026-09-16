import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useBudgetStatus } from './useBudgetStatus'

const STATUS = {
  year: 2026,
  month: 7,
  totalBudgetedCents: 160000,
  totalSpentCents: 5799,
  totalRemainingCents: 154201,
  budgets: [
    {
      id: '01ABC',
      categoryId: '01CAT',
      categoryName: 'Alimentation',
      mode: 'monthly',
      amountCents: 40000,
      currency: 'EUR',
      year: 2026,
      month: 7,
      spentCents: 4599,
      remainingCents: 35401,
      isOverspent: false,
    },
  ],
}

describe('useBudgetStatus', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('fetches the status of the requested period with the bearer token', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(STATUS) }),
    )

    const { result } = renderHook(() => useBudgetStatus(2026, 7))

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.status).toEqual(STATUS)
    expect(fetch).toHaveBeenCalledWith('/api/finance/budget-status?year=2026&month=7', {
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer test-jwt-token',
      },
    })
  })

  test('leaves the status empty when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

    const { result } = renderHook(() => useBudgetStatus(2026, 7))

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.status).toBeNull()
  })
})
