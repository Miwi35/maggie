import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { isValidSalaryDay, useTreasurySettings } from './useTreasurySettings'

describe('useTreasurySettings', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('saving the expected day patches the settings with the day alone', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useTreasurySettings())

    let ok
    await act(async () => {
      ok = await result.current.saveSalaryExpectedDay(25)
    })

    expect(ok).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith('/api/finance/treasury-settings', {
      method: 'PATCH',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: 'Bearer test-jwt-token',
      },
      body: JSON.stringify({ salaryExpectedDay: 25 }),
    })
    expect(result.current.saving).toBe(false)
  })

  test('forgetting the day sends null', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useTreasurySettings())

    await act(async () => {
      await result.current.saveSalaryExpectedDay(null)
    })

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ salaryExpectedDay: null })
  })

  test('a refusal or a network failure is reported, not thrown', async () => {
    const { result } = renderHook(() => useTreasurySettings())

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400 }))
    let refused
    await act(async () => {
      refused = await result.current.saveSalaryExpectedDay(32)
    })
    expect(refused).toBe(false)

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    let failed
    await act(async () => {
      failed = await result.current.saveSalaryExpectedDay(5)
    })
    expect(failed).toBe(false)
    expect(result.current.saving).toBe(false)
  })

  test('only whole days from 1 to 31 are valid', () => {
    expect([0, 1, 31, 32, 2.5, Number.NaN].map(isValidSalaryDay)).toEqual([false, true, true, false, false, false])
  })
})
