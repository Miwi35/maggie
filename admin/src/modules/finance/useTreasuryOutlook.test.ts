import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useTreasuryOutlook } from './useTreasuryOutlook'

const OUTLOOK = { today: '2026-10-13', balanceCents: 0, projection: [] }
const SETTINGS = { id: 's1', salaryExpectedDay: 25, overdraftLimitCents: 0 }
const HEADERS = { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' }

const json = (body: unknown, ok = true) => ({ ok, json: () => Promise.resolve(body) })

describe('useTreasuryOutlook', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('loads the outlook and the settings with the bearer token', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => Promise.resolve(url.endsWith('outlook') ? json(OUTLOOK) : json(SETTINGS))),
    )

    const { result } = renderHook(() => useTreasuryOutlook())
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.outlook).toEqual(OUTLOOK)
    expect(result.current.settings).toEqual(SETTINGS)
    expect(result.current.error).toBeNull()
    expect(fetch).toHaveBeenCalledWith('/api/finance/treasury-outlook', { headers: HEADERS })
    expect(fetch).toHaveBeenCalledWith('/api/finance/treasury-settings', { headers: HEADERS })
  })

  test('a horizon in months is sent to the API, none leaves the default', async () => {
    const fetchMock = vi.fn((url: string) => Promise.resolve(url.includes('outlook') ? json(OUTLOOK) : json(SETTINGS)))
    vi.stubGlobal('fetch', fetchMock)

    const { result, rerender } = renderHook(({ months }) => useTreasuryOutlook(months), {
      initialProps: { months: 6 as number | null },
    })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(fetchMock).toHaveBeenCalledWith('/api/finance/treasury-outlook?months=6', { headers: HEADERS })

    rerender({ months: null })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/finance/treasury-outlook', { headers: HEADERS }))
  })

  test('an API error leaves a message and no outlook', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ error: 'x' }, false)))

    const { result } = renderHook(() => useTreasuryOutlook())
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.outlook).toBeNull()
    expect(result.current.error).toBe('La vue trésorerie est indisponible pour le moment.')
  })

  test('a network failure leaves a message too', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))

    const { result } = renderHook(() => useTreasuryOutlook())
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.error).toBe('La vue trésorerie est indisponible pour le moment.')
  })

  test('save patches the settings, then reads the outlook again', async () => {
    const fetchMock = vi.fn((url: string) => Promise.resolve(url.endsWith('outlook') ? json(OUTLOOK) : json(SETTINGS)))
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useTreasuryOutlook())
    await waitFor(() => expect(result.current.loading).toBe(false))
    fetchMock.mockClear()

    let saved
    await act(async () => {
      saved = await result.current.save({ overdraftLimitCents: 50000 })
    })

    expect(saved).toEqual({ ok: true })
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/finance/treasury-settings', {
      method: 'PATCH',
      headers: { ...HEADERS, 'Content-Type': 'application/json' },
      body: JSON.stringify({ overdraftLimitCents: 50000 }),
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/finance/treasury-outlook', { headers: HEADERS })
  })

  test("save returns the API's message when it refuses", async () => {
    const fetchMock = vi.fn((url: string, init?: RequestInit) =>
      Promise.resolve(
        init?.method === 'PATCH'
          ? json({ error: 'overdraftLimitCents must be an integer of 0 or more cents' }, false)
          : url.endsWith('outlook')
            ? json(OUTLOOK)
            : json(SETTINGS),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useTreasuryOutlook())
    await waitFor(() => expect(result.current.loading).toBe(false))

    let saved
    await act(async () => {
      saved = await result.current.save({ overdraftLimitCents: -1 })
    })

    expect(saved).toEqual({ ok: false, message: 'overdraftLimitCents must be an integer of 0 or more cents' })
  })
})
