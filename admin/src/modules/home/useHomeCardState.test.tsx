import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { emitMercure } from '../../test/mercure'
import { useHomeCardState } from './useHomeCardState'
import type { HomeDay } from './homeDay'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const ok = (body: unknown) => ({ ok: true, json: () => Promise.resolve(body) })

describe('useHomeCardState', () => {
  beforeEach(() => localStorage.setItem('token', 'test-jwt'))
  afterEach(() => vi.unstubAllGlobals())

  test('is loading, then holds what the module answered for the day, with the bearer token', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok({ count: 3 })))

    const { result } = renderHook(() => useHomeCardState<{ count: number }>('tasks', 'today'))

    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.state).toEqual({ count: 3 })
    expect(result.current.error).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/api/home/tasks?day=today', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt' },
    })
  })

  test('reads again when the day changes', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ok({ url })))

    const { result, rerender } = renderHook(({ day }: { day: HomeDay }) => useHomeCardState<{ url: string }>('agenda', day), {
      initialProps: { day: 'today' as HomeDay },
    })
    await waitFor(() => expect(result.current.state?.url).toBe('/api/home/agenda?day=today'))

    rerender({ day: 'tomorrow' })

    await waitFor(() => expect(result.current.state?.url).toBe('/api/home/agenda?day=tomorrow'))
  })

  test('says it failed, without a message of its own, and tries again on demand', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 500, json: () => Promise.resolve({ detail: 'SQLSTATE secret' }) })
      .mockResolvedValueOnce(ok({ count: 1 }))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useHomeCardState<{ count: number }>('finance', 'today'))
    await waitFor(() => expect(result.current.error).toBe(true))

    expect(result.current.loading).toBe(false)
    expect(result.current.state).toBeNull()
    expect(Object.keys(result.current).sort()).toEqual(['error', 'loading', 'retry', 'state'])

    await act(async () => {
      await result.current.retry()
    })

    expect(result.current.error).toBe(false)
    expect(result.current.state).toEqual({ count: 1 })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  test('reads again, without going back to loading, when the module publishes on home/{module}', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(ok({ count: 1 })).mockResolvedValueOnce(ok({ count: 2 }))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useHomeCardState<{ count: number }>('grocery', 'today'))
    await waitFor(() => expect(result.current.state).toEqual({ count: 1 }))

    await act(async () => {
      await emitMercure('/home/grocery', {})
    })

    expect(result.current.loading).toBe(false)
    await waitFor(() => expect(result.current.state).toEqual({ count: 2 }))
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  test('keeps the last state when a refresh fails', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(ok({ count: 1 })).mockRejectedValueOnce(new Error('network down'))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useHomeCardState<{ count: number }>('health', 'today'))
    await waitFor(() => expect(result.current.state).toEqual({ count: 1 }))

    await act(async () => {
      await emitMercure('/home/health', {})
    })

    await waitFor(() => expect(result.current.error).toBe(true))
    expect(result.current.state).toEqual({ count: 1 })
  })
})
