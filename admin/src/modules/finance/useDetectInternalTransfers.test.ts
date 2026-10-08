import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useDetectInternalTransfers } from './useDetectInternalTransfers'

describe('useDetectInternalTransfers', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('posts the rehearsal flag and returns the pairs', async () => {
    const body = { success: true, matched: 1, scanned: 3, dryRun: true, pairs: [] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(body) }))

    const { result } = renderHook(() => useDetectInternalTransfers())

    let report
    await act(async () => {
      report = await result.current.detect(true)
    })

    expect(report).toEqual(body)
    expect(result.current.running).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/api/finance/internal-transfers/detect', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: 'Bearer test-jwt-token',
      },
      body: JSON.stringify({ dryRun: true }),
    })
  })

  test('returns null when the API refuses or the network fails', async () => {
    const { result } = renderHook(() => useDetectInternalTransfers())

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    let refused
    await act(async () => {
      refused = await result.current.detect(false)
    })
    expect(refused).toBeNull()

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    let offline
    await act(async () => {
      offline = await result.current.detect(false)
    })
    expect(offline).toBeNull()
    expect(result.current.running).toBe(false)
  })
})
