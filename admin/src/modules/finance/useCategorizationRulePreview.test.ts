import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  useCategorizationRulePreview,
  type RulePreviewCriteria,
  type RulePreviewResult,
} from './useCategorizationRulePreview'

const CRITERIA: RulePreviewCriteria = {
  labelPattern: 'CARREFOUR',
  matchType: 'contains',
  direction: 'any',
  minAmountCents: null,
  maxAmountCents: null,
  categoryId: '01CAT',
  priority: 0,
  isActive: true,
  ruleId: null,
}

const result = (total: number): RulePreviewResult => ({
  total,
  changeCount: total,
  matches: [],
})

const answer = (ok: boolean, body: unknown) => ({ ok, json: async () => body }) as Response

const fetchMock = vi.fn()

const sentBodies = () =>
  fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string))

describe('useCategorizationRulePreview', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem('token', 'test-jwt-token')
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('asks nothing while the pattern is blank', async () => {
    const { result: hook } = renderHook(() =>
      useCategorizationRulePreview({ ...CRITERIA, labelPattern: '   ' }),
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })

    expect(fetchMock).not.toHaveBeenCalled()
    expect(hook.current.status).toBe('idle')
  })

  test('posts the criteria with the token once typing pauses for 300 ms', async () => {
    fetchMock.mockResolvedValue(answer(true, result(2)))

    const { result: hook } = renderHook(() => useCategorizationRulePreview(CRITERIA))

    expect(hook.current.status).toBe('loading')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(299)
    })
    expect(fetchMock).not.toHaveBeenCalled()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/finance/categorization-rules/preview')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({
      Accept: 'application/json',
      'Content-Type': 'application/json',
      Authorization: 'Bearer test-jwt-token',
    })
    expect(sentBodies()[0]).toEqual(CRITERIA)
    expect(hook.current).toEqual({ status: 'ready', result: result(2), error: null })
  })

  test('sends one request for a burst of keystrokes', async () => {
    fetchMock.mockResolvedValue(answer(true, result(1)))

    const { rerender } = renderHook(
      ({ pattern }) => useCategorizationRulePreview({ ...CRITERIA, labelPattern: pattern }),
      { initialProps: { pattern: 'C' } },
    )

    for (const pattern of ['CA', 'CAR', 'CARR']) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(100)
      })
      rerender({ pattern })
    }
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300)
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(sentBodies()[0].labelPattern).toBe('CARR')
  })

  test('ignores a slow answer that arrives after a newer one', async () => {
    let resolveSlow: (res: Response) => void = () => {}
    fetchMock
      .mockImplementationOnce(() => new Promise<Response>((resolve) => (resolveSlow = resolve)))
      .mockResolvedValueOnce(answer(true, result(7)))

    const { result: hook, rerender } = renderHook(
      ({ pattern }) => useCategorizationRulePreview({ ...CRITERIA, labelPattern: pattern }),
      { initialProps: { pattern: 'CARRE' } },
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(300)
    })
    rerender({ pattern: 'CARREFOUR' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300)
    })
    expect(hook.current.result?.total).toBe(7)

    await act(async () => {
      resolveSlow(answer(true, result(99)))
      await vi.advanceTimersByTimeAsync(0)
    })

    expect(hook.current).toEqual({ status: 'ready', result: result(7), error: null })
  })

  test('keeps the previous result on screen while the next one loads', async () => {
    fetchMock.mockResolvedValueOnce(answer(true, result(3)))

    const { result: hook, rerender } = renderHook(
      ({ pattern }) => useCategorizationRulePreview({ ...CRITERIA, labelPattern: pattern }),
      { initialProps: { pattern: 'CARRE' } },
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300)
    })

    fetchMock.mockReturnValueOnce(new Promise(() => {}))
    rerender({ pattern: 'CARREF' })

    expect(hook.current.status).toBe('loading')
    expect(hook.current.result).toEqual(result(3))
  })

  test('forgets the result when the pattern is cleared', async () => {
    fetchMock.mockResolvedValue(answer(true, result(3)))

    const { result: hook, rerender } = renderHook(
      ({ pattern }) => useCategorizationRulePreview({ ...CRITERIA, labelPattern: pattern }),
      { initialProps: { pattern: 'CARRE' } },
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300)
    })
    rerender({ pattern: '' })

    expect(hook.current).toEqual({ status: 'idle', result: null, error: null })
  })

  test('reports the API message on a 400', async () => {
    fetchMock.mockResolvedValue(answer(false, { error: 'Le montant minimum dépasse le maximum.' }))

    const { result: hook } = renderHook(() => useCategorizationRulePreview(CRITERIA))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300)
    })

    expect(hook.current).toEqual({
      status: 'error',
      result: null,
      error: 'Le montant minimum dépasse le maximum.',
    })
  })

  test('falls back to a generic message when the refusal has no body', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      json: async () => {
        throw new Error('not json')
      },
    } as unknown as Response)

    const { result: hook } = renderHook(() => useCategorizationRulePreview(CRITERIA))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300)
    })

    expect(hook.current.status).toBe('error')
    expect(hook.current.error).toMatch(/indisponible/)
  })

  test('reports a network failure without leaking the browser message', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    const { result: hook } = renderHook(() => useCategorizationRulePreview(CRITERIA))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300)
    })

    expect(hook.current.status).toBe('error')
    expect(hook.current.error).toMatch(/indisponible/)
    expect(hook.current.error).not.toMatch(/Failed to fetch/)
  })
})
