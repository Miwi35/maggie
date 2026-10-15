import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useRecurringOperationPreview } from './useRecurringOperationPreview'
import type { RecurringPreviewCriteria, RecurringPreviewResult } from './useRecurringOperationPreview'

const CRITERIA: RecurringPreviewCriteria = {
  referenceAmountCents: 475000,
  amountTolerancePercent: 10,
  counterpartyName: '',
  labelPattern: '',
  accountId: '',
}

const RESULT: RecurringPreviewResult = {
  total: 1,
  matches: [{ transactionId: 't1', label: 'VIR SEPA XYZ', amountCents: 465000, bookedAt: '2026-10-02', accountId: '01ACC' }],
}

const answer = (ok: boolean, body: unknown) => ({ ok, json: async () => body }) as Response

const fetchMock = vi.fn()

const settle = (ms = 300) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })

describe('useRecurringOperationPreview', () => {
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

  test.each([
    ['no amount yet', { referenceAmountCents: null }],
    ['a zero amount', { referenceAmountCents: 0 }],
    ['a tolerance above 100', { amountTolerancePercent: 101 }],
    ['a negative tolerance', { amountTolerancePercent: -1 }],
  ])('asks nothing with %s', async (_, patch) => {
    const { result } = renderHook(() => useRecurringOperationPreview({ ...CRITERIA, ...patch }))

    await settle(1000)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current).toEqual({ status: 'idle', result: null, error: null })
  })

  test('posts the amount alone, with the token, once typing pauses for 300 ms', async () => {
    fetchMock.mockResolvedValue(answer(true, RESULT))

    const { result } = renderHook(() => useRecurringOperationPreview(CRITERIA))

    expect(result.current.status).toBe('loading')
    await settle(299)
    expect(fetchMock).not.toHaveBeenCalled()
    await settle(1)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/finance/recurring-operations/preview')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer test-jwt-token' })
    expect(JSON.parse(init.body as string)).toEqual({ referenceAmountCents: 475000, amountTolerancePercent: 10 })
    expect(result.current).toEqual({ status: 'ready', result: RESULT, error: null })
  })

  test('sends the identity and the account only when filled, trimmed', async () => {
    fetchMock.mockResolvedValue(answer(true, RESULT))

    renderHook(() =>
      useRecurringOperationPreview({
        ...CRITERIA,
        counterpartyName: ' Acme ',
        labelPattern: ' VIR ',
        accountId: '01ACC',
      }),
    )
    await settle()

    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)).toEqual({
      referenceAmountCents: 475000,
      amountTolerancePercent: 10,
      counterpartyName: 'Acme',
      labelPattern: 'VIR',
      accountId: '01ACC',
    })
  })

  test('reports the API message on a 400', async () => {
    fetchMock.mockResolvedValue(answer(false, { error: 'Le montant de référence ne peut pas être nul.' }))

    const { result } = renderHook(() => useRecurringOperationPreview(CRITERIA))
    await settle()

    expect(result.current).toEqual({ status: 'error', result: null, error: 'Le montant de référence ne peut pas être nul.' })
  })

  test('reports a network failure without leaking the browser message', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    const { result } = renderHook(() => useRecurringOperationPreview(CRITERIA))
    await settle()

    expect(result.current.status).toBe('error')
    expect(result.current.error).toMatch(/indisponible/)
    expect(result.current.error).not.toMatch(/Failed to fetch/)
  })

  test('forgets the result when the amount is cleared', async () => {
    fetchMock.mockResolvedValue(answer(true, RESULT))

    const { result, rerender } = renderHook(({ cents }) => useRecurringOperationPreview({ ...CRITERIA, referenceAmountCents: cents }), {
      initialProps: { cents: 475000 as number | null },
    })
    await settle()
    expect(result.current.status).toBe('ready')

    rerender({ cents: null })

    expect(result.current).toEqual({ status: 'idle', result: null, error: null })
  })
})
