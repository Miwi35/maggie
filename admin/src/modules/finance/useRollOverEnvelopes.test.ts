import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useRollOverEnvelopes } from './useRollOverEnvelopes'

describe('useRollOverEnvelopes', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('posts the source and target periods and returns what was created', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ success: true, created: 2, skipped: 1 }),
      }),
    )

    const { result } = renderHook(() => useRollOverEnvelopes())

    let rolled
    await act(async () => {
      rolled = await result.current.rollOver({
        fromYear: 2026,
        fromMonth: 8,
        year: 2026,
        month: 9,
      })
    })

    expect(rolled).toEqual({ success: true, created: 2, skipped: 1 })
    expect(result.current.running).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/api/finance/rollover-envelopes', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: 'Bearer test-jwt-token',
      },
      body: JSON.stringify({ fromYear: 2026, fromMonth: 8, year: 2026, month: 9 }),
    })
  })

  test('returns null when the API refuses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))

    const { result } = renderHook(() => useRollOverEnvelopes())

    let rolled
    await act(async () => {
      rolled = await result.current.rollOver({
        fromYear: 2026,
        fromMonth: 8,
        year: 2026,
        month: 9,
      })
    })

    expect(rolled).toBeNull()
    expect(result.current.running).toBe(false)
  })
})
