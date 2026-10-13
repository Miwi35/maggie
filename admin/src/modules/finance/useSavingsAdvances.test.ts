import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { localDay, useSavingsAdvances } from './useSavingsAdvances'
import type { SavingsAdvancesData } from './useSavingsAdvances'

const mercureListeners: Array<(data?: string) => void> = []
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], cb: (data?: string) => void) => {
    mercureListeners.push(cb)
  },
}))

const DATA: SavingsAdvancesData = {
  advances: [
    {
      id: 'adv-1',
      sourceAccountId: 'livret',
      sourceAccountName: 'Livret',
      amountCents: 150000,
      openedOn: '2026-10-11',
      repaidCents: 0,
      outstandingCents: 150000,
      isClosed: false,
    },
  ],
  bySource: [{ sourceAccountId: 'livret', sourceAccountName: 'Livret', outstandingCents: 150000, openCount: 1 }],
  totalOutstandingCents: 150000,
  salaryDelay: {
    isConfigured: true,
    salaryExpectedDay: 25,
    referenceCents: 495000,
    isLate: true,
    delayDays: 30,
    shortfallCents: 495000,
  },
  proposals: [
    {
      fromAccountId: 'courant',
      toAccountId: 'livret',
      amountCents: 150000,
      advanceId: 'adv-1',
      label: 'Remise avance Livret',
      currency: 'EUR',
      isPartial: false,
    },
  ],
  proposalsTotalCents: 150000,
  unproposed: [],
  unproposedCents: 0,
}

const json = (body: unknown, ok = true) => ({ ok, json: () => Promise.resolve(body) })

describe('useSavingsAdvances', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mercureListeners.length = 0
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('loads the advances with the session token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(DATA))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useSavingsAdvances())
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.data).toEqual(DATA)
    expect(result.current.error).toBeNull()
    expect(fetchMock).toHaveBeenCalledWith('/api/finance/savings-advances', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' },
    })
  })

  test('an API refusal is reported as an error, without data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({}, false)))

    const { result } = renderHook(() => useSavingsAdvances())
    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.data).toBeNull()
    expect(result.current.error).toBe("Impossible de lire les avances d'épargne")
  })

  test('a network failure is reported, and retrying recovers', async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(json(DATA))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useSavingsAdvances())
    await waitFor(() => expect(result.current.error).not.toBeNull())

    act(() => result.current.retry())
    await waitFor(() => expect(result.current.data).toEqual(DATA))
    expect(result.current.error).toBeNull()
  })

  test('a Mercure message reads the advances again', async () => {
    const settled = { ...DATA, advances: [{ ...DATA.advances[0], outstandingCents: 0, isClosed: true }], totalOutstandingCents: 0 }
    const fetchMock = vi.fn().mockResolvedValueOnce(json(DATA)).mockResolvedValue(json(settled))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useSavingsAdvances())
    await waitFor(() => expect(result.current.data?.totalOutstandingCents).toBe(150000))

    await act(async () => mercureListeners.at(-1)?.('{}'))

    await waitFor(() => expect(result.current.data?.totalOutstandingCents).toBe(0))
  })

  test('« Je l’ai fait » records the debit, then the credit, then reads again', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(DATA))
      .mockResolvedValueOnce(json({}))
      .mockResolvedValueOnce(json({}))
      .mockResolvedValue(json({ ...DATA, totalOutstandingCents: 0, proposals: [] }))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useSavingsAdvances())
    await waitFor(() => expect(result.current.loading).toBe(false))

    let ok
    await act(async () => {
      ok = await result.current.giveBack(DATA.proposals[0])
    })

    expect(ok).toBe(true)
    const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(posts.map(([path]) => path)).toEqual(['/api/transactions', '/api/transactions'])
    const [debit, credit] = posts.map(([, init]) => JSON.parse(init.body))
    expect(debit).toEqual({
      account: '/api/accounts/courant',
      label: 'Remise avance Livret',
      amountCents: -150000,
      currency: 'EUR',
      bookedAt: localDay(),
      status: 'spent',
    })
    expect(credit).toEqual({ ...debit, account: '/api/accounts/livret', amountCents: 150000 })
    expect(posts[0][1].headers['Content-Type']).toBe('application/ld+json')
    await waitFor(() => expect(result.current.data?.totalOutstandingCents).toBe(0))
    expect(result.current.givingBack).toBeNull()
  })

  test('when the debit is refused, the credit is not recorded', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json(DATA)).mockResolvedValueOnce(json({}, false)).mockResolvedValue(json(DATA))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useSavingsAdvances())
    await waitFor(() => expect(result.current.loading).toBe(false))

    let ok
    await act(async () => {
      ok = await result.current.giveBack(DATA.proposals[0])
    })

    expect(ok).toBe(false)
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    expect(result.current.givingBack).toBeNull()
  })

  test('localDay writes the calendar day with zero padding', () => {
    expect(localDay(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05')
  })
})
