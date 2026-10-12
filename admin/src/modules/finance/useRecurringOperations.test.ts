import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import {
  useAttachRecurringOperations,
  useRecurringLink,
  useRecurringOverview,
  useRecurringSuggestions,
} from './useRecurringOperations'

const fetchMock = vi.fn()

const respond = (ok: boolean, body: unknown = {}) => Promise.resolve({ ok, json: async () => body } as Response)

const lastCall = () => {
  const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit]

  return { url, init, body: init.body ? JSON.parse(init.body as string) : undefined }
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  localStorage.setItem('token', 'jwt')
})

afterEach(() => {
  vi.unstubAllGlobals()
  fetchMock.mockReset()
  localStorage.clear()
})

describe('useRecurringOverview', () => {
  test('reads the overview with the bearer', async () => {
    const overview = { operations: [], proposals: [], totals: { activeCount: 0 } }
    fetchMock.mockImplementation(() => respond(true, overview))

    const { result } = renderHook(() => useRecurringOverview())

    await waitFor(() => expect(result.current.overview).toEqual(overview))
    expect(result.current.error).toBeNull()
    const { url, init } = lastCall()
    expect(url).toBe('/api/finance/recurring-operations/overview')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer jwt')
  })

  test('an API error becomes a message, not an exception', async () => {
    fetchMock.mockImplementation(() => respond(false))

    const { result } = renderHook(() => useRecurringOverview())

    await waitFor(() => expect(result.current.error).toBe('Impossible de lire les opérations récurrentes'))
    expect(result.current.overview).toBeNull()
  })

  test('a network failure becomes the same message, and a retry clears it', async () => {
    fetchMock.mockImplementationOnce(() => Promise.reject(new Error('offline')))
    const { result } = renderHook(() => useRecurringOverview())
    await waitFor(() => expect(result.current.error).not.toBeNull())

    fetchMock.mockImplementation(() => respond(true, { operations: [], proposals: [], totals: {} }))
    await act(async () => result.current.reload())

    await waitFor(() => expect(result.current.error).toBeNull())
    expect(result.current.overview).not.toBeNull()
  })
})

describe('useRecurringSuggestions', () => {
  test('reads the suggestions', async () => {
    fetchMock.mockImplementation(() => respond(true, { suggestions: [{ label: 'Netflix' }] }))

    const { result } = renderHook(() => useRecurringSuggestions())

    await waitFor(() => expect(result.current.suggestions).toEqual([{ label: 'Netflix' }]))
  })

  test('accepting posts the chosen suggestions and returns what was created', async () => {
    fetchMock.mockImplementation(() => respond(true, { suggestions: [] }))
    const { result } = renderHook(() => useRecurringSuggestions())
    await waitFor(() => expect(result.current.suggestions).toEqual([]))
    fetchMock.mockImplementation(() => respond(true, { created: 1, labels: ['Netflix'], attached: 11, proposed: 0 }))

    let accepted: unknown
    await act(async () => {
      accepted = await result.current.accept([{ label: 'Netflix' } as never])
    })

    expect(accepted).toEqual({ created: 1, labels: ['Netflix'], attached: 11, proposed: 0 })
    expect(lastCall()).toMatchObject({
      url: '/api/finance/recurring-operations/suggestions/accept',
      body: { suggestions: [{ label: 'Netflix' }] },
    })
    expect(lastCall().init.method).toBe('POST')
  })

  test('a refused acceptance returns null', async () => {
    fetchMock.mockImplementation(() => respond(true, { suggestions: [] }))
    const { result } = renderHook(() => useRecurringSuggestions())
    await waitFor(() => expect(result.current.suggestions).toEqual([]))
    fetchMock.mockImplementation(() => respond(false))

    let accepted: unknown = 'unset'
    await act(async () => {
      accepted = await result.current.accept([{ label: 'Netflix' } as never])
    })

    expect(accepted).toBeNull()
  })
})

describe('useAttachRecurringOperations', () => {
  test('a rehearsal posts dryRun true and returns the report', async () => {
    const report = { dryRun: true, scanned: 3, attached: 1, proposed: 0, attachments: [], proposals: [] }
    fetchMock.mockImplementation(() => respond(true, report))
    const { result } = renderHook(() => useAttachRecurringOperations())

    let got: unknown
    await act(async () => {
      got = await result.current.attach(true)
    })

    expect(got).toEqual(report)
    expect(lastCall()).toMatchObject({ url: '/api/finance/recurring-operations/attach', body: { dryRun: true } })
    expect(result.current.running).toBe(false)
  })

  test('the real pass posts dryRun false; a failure returns null and stops running', async () => {
    fetchMock.mockImplementation(() => respond(false))
    const { result } = renderHook(() => useAttachRecurringOperations())

    let got: unknown = 'unset'
    await act(async () => {
      got = await result.current.attach(false)
    })

    expect(got).toBeNull()
    expect(lastCall().body).toEqual({ dryRun: false })
    expect(result.current.running).toBe(false)
  })
})

describe('useRecurringLink', () => {
  test('attaching patches the line with the series IRI and the occurrence, as merge-patch', async () => {
    fetchMock.mockImplementation(() => respond(true))
    const { result } = renderHook(() => useRecurringLink())

    let ok = false
    await act(async () => {
      ok = await result.current.attach('/api/transactions/01TX', '01OP', '2026-11-12T00:00:00+00:00')
    })

    expect(ok).toBe(true)
    const { url, init, body } = lastCall()
    expect(url).toBe('/api/transactions/01TX')
    expect(init.method).toBe('PATCH')
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/merge-patch+json')
    expect(body).toEqual({ recurringOperation: '/api/recurring_operations/01OP', recurringOccurrenceOn: '2026-11-12' })
  })

  test('detaching clears both fields', async () => {
    fetchMock.mockImplementation(() => respond(true))
    const { result } = renderHook(() => useRecurringLink())

    await act(async () => {
      await result.current.detach('01TX')
    })

    expect(lastCall().url).toBe('/api/transactions/01TX')
    expect(lastCall().body).toEqual({ recurringOperation: null, recurringOccurrenceOn: null })
  })

  test('a refused patch returns false', async () => {
    fetchMock.mockImplementation(() => respond(false))
    const { result } = renderHook(() => useRecurringLink())

    let ok = true
    await act(async () => {
      ok = await result.current.detach('01TX')
    })

    expect(ok).toBe(false)
    expect(result.current.saving).toBe(false)
  })
})
