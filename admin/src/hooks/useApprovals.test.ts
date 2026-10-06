import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { APPROVAL_ERRORS, isAwaitingAnswer, useApprovals, type Approval } from './useApprovals'

const IN_ONE_HOUR = new Date(Date.now() + 60 * 60 * 1000).toISOString()

function action(overrides: Partial<Approval> = {}): Approval {
  return {
    id: 'a1',
    toolName: 'delete_event',
    arguments: { id: 'evt-42' },
    status: 'pending',
    result: null,
    createdAt: '2026-10-06T08:00:00.000Z',
    expiresAt: IN_ONE_HOUR,
    ...overrides,
  }
}

function response(status: number, body: unknown = {}) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) })
}

describe('useApprovals', () => {
  beforeEach(() => {
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    localStorage.setItem('token', 'jwt')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('loads the pending actions on mount', async () => {
    const fetchMock = vi.fn(() => response(200, [action(), action({ id: 'a2' })]))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useApprovals())

    await waitFor(() => expect(result.current.approvals.map((a) => a.id)).toEqual(['a1', 'a2']))
    expect(fetchMock).toHaveBeenCalledWith('/agent/approvals?status=pending', {
      headers: { Authorization: 'Bearer jwt' },
    })
  })

  test('shows no card when the list cannot be loaded', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new Error('offline')))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useApprovals())

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(result.current.approvals).toEqual([])
  })

  test('opens no connection of its own: the caller feeds the stream through receive', () => {
    const eventSource = vi.fn()
    vi.stubGlobal('EventSource', eventSource)
    vi.stubGlobal('fetch', vi.fn(() => response(200, [])))

    renderHook(() => useApprovals())

    expect(eventSource).not.toHaveBeenCalled()
  })

  test('adds an action and follows its status from the messages it receives', async () => {
    vi.stubGlobal('fetch', vi.fn(() => response(200, [])))
    const { result } = renderHook(() => useApprovals())

    act(() => {
      expect(result.current.receive(action())).toBe(true)
    })
    expect(result.current.approvals).toHaveLength(1)
    expect(result.current.approvals[0].status).toBe('pending')

    act(() => {
      result.current.receive(action({ status: 'approved', result: '{"deleted":true}' }))
    })
    expect(result.current.approvals).toHaveLength(1)
    expect(result.current.approvals[0]).toMatchObject({ status: 'approved', result: '{"deleted":true}' })
  })

  test('declines what is not an approval, so the caller can use it', () => {
    vi.stubGlobal('fetch', vi.fn(() => response(200, [])))
    const { result } = renderHook(() => useApprovals())

    act(() => {
      expect(result.current.receive({ id: 'c1', label: 'Courses', status: 'active' })).toBe(false)
      expect(result.current.receive(null)).toBe(false)
      expect(result.current.receive('not an approval')).toBe(false)
    })

    expect(result.current.approvals).toEqual([])
  })

  test('approve POSTs, stays busy until it returns, then shows the settled action', async () => {
    let finish: (value: unknown) => void = () => {}
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return new Promise((resolve) => (finish = resolve))
      return response(200, [action()])
    })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useApprovals())
    await waitFor(() => expect(result.current.approvals).toHaveLength(1))

    let pending: Promise<void> = Promise.resolve()
    act(() => {
      pending = result.current.approve('a1')
    })

    expect(result.current.approvals[0].busy).toBe('approve')
    expect(fetchMock).toHaveBeenCalledWith('/agent/approvals/a1/approve', {
      method: 'POST',
      headers: { Authorization: 'Bearer jwt' },
    })

    await act(async () => {
      finish({ ok: true, status: 200, json: () => Promise.resolve(action({ status: 'approved', result: '{}' })) })
      await pending
    })

    expect(result.current.approvals[0]).toMatchObject({ status: 'approved', result: '{}', busy: undefined })
  })

  test('deny POSTs to the deny endpoint', async () => {
    const fetchMock = vi.fn((_url: string, init?: RequestInit) =>
      init?.method === 'POST' ? response(200, action({ status: 'denied' })) : response(200, [action()]),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useApprovals())
    await waitFor(() => expect(result.current.approvals).toHaveLength(1))

    await act(() => result.current.deny('a1'))

    expect(fetchMock).toHaveBeenCalledWith('/agent/approvals/a1/deny', expect.objectContaining({ method: 'POST' }))
    expect(result.current.approvals[0].status).toBe('denied')
  })

  test('an error leaves the action pending, with a message, so the user can retry', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) =>
        init?.method === 'POST' ? response(500) : response(200, [action()]),
      ),
    )
    const { result } = renderHook(() => useApprovals())
    await waitFor(() => expect(result.current.approvals).toHaveLength(1))

    await act(() => result.current.approve('a1'))

    expect(result.current.approvals[0]).toMatchObject({
      status: 'pending',
      busy: undefined,
      error: APPROVAL_ERRORS.network,
    })
  })

  test('a network failure is reported the same way', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) =>
        init?.method === 'POST' ? Promise.reject(new Error('offline')) : response(200, [action()]),
      ),
    )
    const { result } = renderHook(() => useApprovals())
    await waitFor(() => expect(result.current.approvals).toHaveLength(1))

    await act(() => result.current.approve('a1'))

    expect(result.current.approvals[0].error).toBe(APPROVAL_ERRORS.network)
  })

  test('410 turns the card expired', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) =>
        init?.method === 'POST' ? response(410) : response(200, [action()]),
      ),
    )
    const { result } = renderHook(() => useApprovals())
    await waitFor(() => expect(result.current.approvals).toHaveLength(1))

    await act(() => result.current.approve('a1'))

    expect(result.current.approvals[0]).toMatchObject({ status: 'expired', error: undefined })
  })

  test('409 says the action was already answered, then Mercure brings its real status', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) =>
        init?.method === 'POST' ? response(409) : response(200, [action()]),
      ),
    )
    const { result } = renderHook(() => useApprovals())
    await waitFor(() => expect(result.current.approvals).toHaveLength(1))

    await act(() => result.current.approve('a1'))
    expect(result.current.approvals[0].error).toBe(APPROVAL_ERRORS.alreadyDecided)

    act(() => {
      result.current.receive(action({ status: 'denied' }))
    })
    expect(result.current.approvals[0]).toMatchObject({ status: 'denied', error: undefined })
  })

  test('404 removes the card', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) =>
        init?.method === 'POST' ? response(404) : response(200, [action()]),
      ),
    )
    const { result } = renderHook(() => useApprovals())
    await waitFor(() => expect(result.current.approvals).toHaveLength(1))

    await act(() => result.current.approve('a1'))

    expect(result.current.approvals).toEqual([])
  })
})

describe('isAwaitingAnswer', () => {
  test('is true only for a pending action inside its window', () => {
    expect(isAwaitingAnswer(action())).toBe(true)
    expect(isAwaitingAnswer(action({ expiresAt: '2020-01-01T00:00:00.000Z' }))).toBe(false)
    expect(isAwaitingAnswer(action({ status: 'approved' }))).toBe(false)
  })
})
