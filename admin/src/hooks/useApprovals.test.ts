import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { APPROVAL_ERRORS, isAwaitingAnswer, useApprovals, type Approval } from './useApprovals'

class MockEventSource {
  static instances: MockEventSource[] = []
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(
    public url: string,
    public init?: EventSourceInit,
  ) {
    MockEventSource.instances.push(this)
  }
  emit(data: unknown) {
    this.onmessage?.({ data: typeof data === 'string' ? data : JSON.stringify(data) } as MessageEvent)
  }
}

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
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
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

  test('subscribes to the approvals topic of the current user, with credentials', () => {
    vi.stubGlobal('fetch', vi.fn(() => response(200, [])))

    renderHook(() => useApprovals())

    expect(MockEventSource.instances).toHaveLength(1)
    const [es] = MockEventSource.instances
    expect(new URL(es.url, 'http://localhost').searchParams.get('match')).toBe('/approvals/user-1')
    expect(es.init?.withCredentials).toBe(true)
  })

  test('adds an action and follows its status from Mercure messages', async () => {
    vi.stubGlobal('fetch', vi.fn(() => response(200, [])))
    const { result } = renderHook(() => useApprovals())
    const [es] = MockEventSource.instances

    act(() => es.emit(action()))
    expect(result.current.approvals).toHaveLength(1)
    expect(result.current.approvals[0].status).toBe('pending')

    act(() => es.emit(action({ status: 'approved', result: '{"deleted":true}' })))
    expect(result.current.approvals).toHaveLength(1)
    expect(result.current.approvals[0]).toMatchObject({ status: 'approved', result: '{"deleted":true}' })
  })

  test('ignores malformed Mercure messages', () => {
    vi.stubGlobal('fetch', vi.fn(() => response(200, [])))
    const { result } = renderHook(() => useApprovals())
    const [es] = MockEventSource.instances

    act(() => es.emit('not json'))
    act(() => es.emit({ unrelated: true }))

    expect(result.current.approvals).toEqual([])
  })

  test('closes the subscription on unmount', () => {
    vi.stubGlobal('fetch', vi.fn(() => response(200, [])))
    const { unmount } = renderHook(() => useApprovals())

    unmount()

    expect(MockEventSource.instances[0].close).toHaveBeenCalled()
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

    act(() => MockEventSource.instances[0].emit(action({ status: 'denied' })))
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
