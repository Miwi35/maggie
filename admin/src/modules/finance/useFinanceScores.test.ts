import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { reasonText, scoreDate, useFinanceScores } from './useFinanceScores'
import { scores } from './scoresFixture'

describe('useFinanceScores', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('loads the scores of the period on screen', async () => {
    const today = new Date()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(scores()) }),
    )

    const { result } = renderHook(() => useFinanceScores(today.getFullYear(), today.getMonth() + 1))
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.scores).toEqual(scores())
    expect(result.current.error).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/api/finance/scores', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' },
    })
  })

  test('asks for the last day of a past month', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(scores()) }),
    )

    const { result } = renderHook(() => useFinanceScores(2020, 2))
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/finance/scores?date=2020-02-29')
  })

  test('reports an error when the API fails, and keeps the scores it had', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve(scores()) })
      .mockResolvedValueOnce({ ok: false, status: 500 })
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useFinanceScores(2020, 2))
    await waitFor(() => expect(result.current.scores).not.toBeNull())

    await act(async () => {
      await result.current.refresh()
    })

    expect(result.current.error).toBe(true)
    expect(result.current.scores).not.toBeNull()
  })

  test('reports an error when the network is down', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

    const { result } = renderHook(() => useFinanceScores(2020, 2))
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.error).toBe(true)
    expect(result.current.scores).toBeNull()
  })
})

describe('scoreDate', () => {
  const now = new Date(2026, 9, 13)

  test('is the API default for the current month', () => {
    expect(scoreDate(2026, 10, now)).toBeUndefined()
  })

  test('is the last day of a past month and the first of a month to come', () => {
    expect(scoreDate(2026, 9, now)).toBe('2026-09-30')
    expect(scoreDate(2026, 11, now)).toBe('2026-11-01')
  })
})

describe('reasonText', () => {
  test('words the rhythm reasons', () => {
    expect(reasonText({ code: 'above_rhythm', amountCents: 5000 })).toContain('de plus que le rythme')
    expect(reasonText({ code: 'week_budget_exceeded', amountCents: 5000 })).toContain('de la semaine')
  })

  test('an unknown code falls back to itself rather than breaking', () => {
    expect(reasonText({ code: 'something_new' })).toBe('something_new')
  })
})
