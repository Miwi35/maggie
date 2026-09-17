import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useApplyCategorizationRules } from './useApplyCategorizationRules'

describe('useApplyCategorizationRules', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('posts to the API and returns what was categorized', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ success: true, categorized: 3, scanned: 12 }),
      }),
    )

    const { result } = renderHook(() => useApplyCategorizationRules())

    let applied
    await act(async () => {
      applied = await result.current.apply()
    })

    expect(applied).toEqual({ success: true, categorized: 3, scanned: 12 })
    expect(result.current.running).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/api/finance/apply-categorization-rules', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer test-jwt-token',
      },
    })
  })

  test('returns null when the API refuses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))

    const { result } = renderHook(() => useApplyCategorizationRules())

    let applied
    await act(async () => {
      applied = await result.current.apply()
    })

    expect(applied).toBeNull()
    expect(result.current.result).toBeNull()
    expect(result.current.running).toBe(false)
  })

  test('returns null when the request throws', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

    const { result } = renderHook(() => useApplyCategorizationRules())

    let applied
    await act(async () => {
      applied = await result.current.apply()
    })

    expect(applied).toBeNull()
    expect(result.current.running).toBe(false)
  })
})
