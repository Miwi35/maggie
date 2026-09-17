import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useCushionStatus, CUSHION_STATE_LABELS } from './useCushionStatus'

const STATUS = {
  state: 'building',
  targetMonths: 3,
  monthlyNetIncomeCents: 250000,
  targetCents: 750000,
  currentCents: 450000,
  deficitCents: 300000,
  coveragePercent: 60,
  monthsCovered: 1.8,
  rechargeCapCents: 15000,
  rechargeTargetMonths: 6,
  monthlyRechargeCents: 15000,
  rechargeMonths: 20,
  isCappedByRechargeCap: true,
  blocksGreenScore: true,
  isConfigured: true,
  accounts: [],
}

describe('useCushionStatus', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('loads the status with the bearer token', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(STATUS) }),
    )

    const { result } = renderHook(() => useCushionStatus())

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.status).toEqual(STATUS)
    expect(fetch).toHaveBeenCalledWith('/api/finance/cushion-status', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' },
    })
  })

  test('configure patches the settings and keeps the returned status', async () => {
    const updated = { ...STATUS, targetMonths: 6, targetCents: 1500000 }
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(STATUS) })
        .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(updated) }),
    )

    const { result } = renderHook(() => useCushionStatus())
    await waitFor(() => expect(result.current.loading).toBe(false))

    let ok
    await act(async () => {
      ok = await result.current.configure({ targetMonths: 6 })
    })

    expect(ok).toBe(true)
    expect(result.current.status?.targetMonths).toBe(6)
    expect(fetch).toHaveBeenLastCalledWith('/api/finance/cushion-config', {
      method: 'PATCH',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer test-jwt-token',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ targetMonths: 6 }),
    })
  })

  test('configure reports failure without dropping the current status', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(STATUS) })
        .mockResolvedValueOnce({ ok: false }),
    )

    const { result } = renderHook(() => useCushionStatus())
    await waitFor(() => expect(result.current.loading).toBe(false))

    let ok
    await act(async () => {
      ok = await result.current.configure({ targetMonths: 0 })
    })

    expect(ok).toBe(false)
    expect(result.current.status?.targetMonths).toBe(3)
  })
})

describe('CUSHION_STATE_LABELS', () => {
  test('covers the three API states', () => {
    expect(Object.keys(CUSHION_STATE_LABELS)).toEqual(['building', 'complete', 'recharging'])
    expect(CUSHION_STATE_LABELS.recharging).toBe('En recharge')
  })
})
