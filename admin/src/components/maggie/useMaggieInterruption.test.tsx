import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { LATER_DELAY_MS, useMaggieInterruption } from './useMaggieInterruption'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onmessage: ((event: { data: string }) => void) | null = null
  close = vi.fn()
  constructor(
    public url: string,
    public init?: EventSourceInit,
  ) {
    FakeEventSource.instances.push(this)
  }
  emit(payload: unknown) {
    act(() => this.onmessage?.({ data: JSON.stringify(payload) }))
  }
}

const proaction = (overrides: Record<string, unknown> = {}) => ({
  id: 'p1',
  status: 'completed',
  response: 'Ton colis est arrivé.',
  ...overrides,
})

const notification = (overrides: Record<string, unknown> = {}) => ({
  '@id': '/api/notifications/n1',
  id: 'n1',
  type: 'reminder',
  title: 'close 27',
  body: '15',
  relatedEntityIri: '/api/events/e1',
  readAt: null,
  createdAt: '2026-10-07T10:00:00+00:00',
  ...overrides,
})

const approval = (overrides: Record<string, unknown> = {}) => ({
  id: 'a1',
  toolName: 'delete_event',
  summary: 'Supprimer l’événement « Psychomot »',
  status: 'pending',
  expiresAt: '2099-01-01T00:00:00+00:00',
  ...overrides,
})

const stream = () => FakeEventSource.instances[0]

const fetchPending = vi.fn()

function setup(chatOpen = false) {
  const onOpenChat = vi.fn()
  const hook = renderHook((props: { chatOpen: boolean }) => useMaggieInterruption({ ...props, onOpenChat }), {
    initialProps: { chatOpen },
  })
  return { ...hook, onOpenChat }
}

describe('useMaggieInterruption', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
    fetchPending.mockReset().mockResolvedValue({ ok: true, json: async () => [] })
    vi.stubGlobal('fetch', fetchPending)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('subscribes to the user proactions topic, with credentials', () => {
    setup()

    expect(decodeURIComponent(stream().url)).toContain('/proactions/u1')
    expect(stream().init).toEqual({ withCredentials: true })
  })

  test('ignores a context update arriving on the shared feed', () => {
    const { result } = setup()

    stream().emit({ id: 'c1', label: 'Courses', status: 'active', summary: null })

    expect(result.current.current).toBeNull()
  })

  test('does not subscribe without a signed-in user', () => {
    localStorage.clear()

    setup()

    expect(FakeEventSource.instances).toHaveLength(0)
  })

  test('closes the stream on unmount', () => {
    const { unmount } = setup()

    unmount()

    expect(stream().close).toHaveBeenCalled()
  })

  test('a completed proaction with an answer interrupts', () => {
    const { result } = setup()

    stream().emit(proaction())

    expect(result.current.current).toEqual({
      source: 'proaction',
      id: 'proaction:p1',
      ref: 'p1',
      message: 'Ton colis est arrivé.',
    })
  })

  test.each([
    ['pending', { status: 'pending' }],
    ['running', { status: 'running' }],
    ['failed', { status: 'failed' }],
    ['empty', { response: '' }],
    ['blank', { response: '   ' }],
    ['missing', { response: null }],
  ])('a %s proaction stays quiet', (_label, overrides) => {
    const { result } = setup()

    stream().emit(proaction(overrides))

    expect(result.current.current).toBeNull()
  })

  test('ignores a malformed message', () => {
    const { result } = setup()

    act(() => stream().onmessage?.({ data: 'not json' }))

    expect(result.current.current).toBeNull()
  })

  test('the same proaction twice interrupts once', () => {
    const { result } = setup()

    stream().emit(proaction())
    act(() => result.current.dismiss(false))
    stream().emit(proaction())

    expect(result.current.current).toBeNull()
  })

  test('several arrivals queue up and show one at a time', () => {
    const { result } = setup()

    stream().emit(proaction({ id: 'a', response: 'Première' }))
    stream().emit(proaction({ id: 'b', response: 'Seconde' }))
    expect(result.current.current?.message).toBe('Première')

    act(() => result.current.dismiss(false))

    expect(result.current.current?.message).toBe('Seconde')
  })

  test('« Plus tard » brings it back after the delay', () => {
    const { result } = setup()
    stream().emit(proaction())

    act(() => result.current.dismiss(true))
    expect(result.current.current).toBeNull()

    act(() => vi.advanceTimersByTime(LATER_DELAY_MS - 1))
    expect(result.current.current).toBeNull()
    act(() => vi.advanceTimersByTime(1))
    expect(result.current.current?.message).toBe('Ton colis est arrivé.')
  })

  test('opening the chat from it closes it for good', () => {
    const { result, onOpenChat } = setup()
    stream().emit(proaction())

    act(() => result.current.openChat())

    expect(onOpenChat).toHaveBeenCalledTimes(1)
    expect(result.current.current).toBeNull()
    act(() => vi.advanceTimersByTime(LATER_DELAY_MS * 2))
    expect(result.current.current).toBeNull()
  })

  test('the chat opening elsewhere closes it and cancels the return', () => {
    const { result, rerender } = setup()
    stream().emit(proaction({ id: 'a' }))
    stream().emit(proaction({ id: 'b' }))
    act(() => result.current.dismiss(true))

    rerender({ chatOpen: true })

    expect(result.current.current).toBeNull()
    act(() => vi.advanceTimersByTime(LATER_DELAY_MS * 2))
    expect(result.current.current).toBeNull()
  })

  test('a proaction arriving while the chat is open does not queue behind it', () => {
    const { result, rerender } = setup(true)

    stream().emit(proaction())
    expect(result.current.current).toBeNull()

    rerender({ chatOpen: false })

    expect(result.current.current).toBeNull()
    // …and it is not shown later either: the chat already had it.
    stream().emit(proaction())
    expect(result.current.current).toBeNull()
  })

  test('a notification interrupts even over an open chat, which does not show it', () => {
    const { result } = setup(true)

    stream().emit(notification())

    expect(result.current.current?.source).toBe('notification')
  })

  test('opening the chat keeps the notifications waiting', () => {
    const { result, rerender } = setup()
    stream().emit(proaction())
    stream().emit(notification())
    act(() => result.current.dismiss(false))
    stream().emit(proaction({ id: 'p2' }))

    rerender({ chatOpen: true })

    expect(result.current.current?.source).toBe('notification')
    act(() => result.current.dismiss(false))
    expect(result.current.current).toBeNull()
  })

  test('a proaction arriving once the chat is closed again interrupts', () => {
    const { result, rerender } = setup(true)
    rerender({ chatOpen: false })

    stream().emit(proaction({ id: 'late' }))

    expect(result.current.current?.ref).toBe('late')
  })

  describe('notifications', () => {
    test('subscribes to the notifications topic, scoped to the user', () => {
      setup()

      expect(new URL(stream().url).searchParams.getAll('match_urlpattern')).toContain('/users/u1/api/notifications/:id')
    })

    test('an event reminder interrupts with its title, its time and the way to the event', () => {
      const { result } = setup()

      stream().emit(notification())

      expect(result.current.current).toMatchObject({
        source: 'notification',
        ref: '/api/notifications/n1',
        title: 'close 27',
        message: 'Dans 15 min',
        link: { label: "Voir l'événement", path: '/calendar?eventId=%2Fapi%2Fevents%2Fe1' },
      })
    })

    test.each(['reminder', 'proaction', 'task_due', 'grocery', 'finance', 'consent_expiring'])(
      'a %s notification interrupts',
      (type) => {
        const { result } = setup()

        stream().emit(notification({ type, body: 'Détail' }))

        expect(result.current.current).toMatchObject({ source: 'notification', title: 'close 27', message: 'Détail' })
      },
    )

    test('a notification with nowhere to go has no link', () => {
      const { result } = setup()

      stream().emit(notification({ type: 'proaction', body: null, relatedEntityIri: null }))

      expect(result.current.current).toMatchObject({ source: 'notification', message: '', link: null })
    })

    test.each([
      ['read', { readAt: '2026-10-07T10:01:00+00:00' }],
      ['deleted', { deleted: true, title: undefined }],
      ['an update without a title', { title: undefined }],
      ['an approval notification, which the approval itself carries', { type: 'approval' }],
    ])('%s stays quiet', (_label, overrides) => {
      const { result } = setup()

      stream().emit(notification(overrides))

      expect(result.current.current).toBeNull()
    })

    test('the same notification twice interrupts once', () => {
      const { result } = setup()

      stream().emit(notification())
      act(() => result.current.dismiss(false))
      stream().emit(notification())

      expect(result.current.current).toBeNull()
    })

    test('three notifications arriving together pass one after the other', () => {
      const { result } = setup()

      stream().emit(notification({ '@id': '/api/notifications/a', title: 'Première' }))
      stream().emit(notification({ '@id': '/api/notifications/b', title: 'Seconde' }))
      stream().emit(notification({ '@id': '/api/notifications/c', title: 'Troisième' }))

      const shown: string[] = []
      for (let i = 0; i < 3; i++) {
        shown.push(result.current.current?.title ?? '')
        act(() => result.current.dismiss(false))
      }
      expect(shown).toEqual(['Première', 'Seconde', 'Troisième'])
      expect(result.current.current).toBeNull()
    })

    test('a notification, a proaction and an approval share the one queue', () => {
      const { result } = setup()

      stream().emit(notification())
      stream().emit(proaction())
      stream().emit(approval())

      const order: string[] = []
      for (let i = 0; i < 3; i++) {
        order.push(result.current.current?.source ?? '')
        act(() => result.current.dismiss(false))
      }
      expect(order).toEqual(['notification', 'proaction', 'approval'])
    })

    test('a notification put off comes back, unread', () => {
      const { result } = setup(true)
      stream().emit(notification())

      act(() => result.current.dismiss(true))
      expect(result.current.current).toBeNull()
      act(() => vi.advanceTimersByTime(LATER_DELAY_MS))

      expect(result.current.current?.ref).toBe('/api/notifications/n1')
    })
  })

  describe('approvals', () => {
    test('subscribes to the approvals topic', () => {
      setup()

      expect(new URL(stream().url).searchParams.getAll('match')).toContain('/approvals/u1')
    })

    test('an action waiting for an answer interrupts with its summary', () => {
      const { result } = setup()

      stream().emit(approval())

      expect(result.current.current).toEqual({
        source: 'approval',
        id: 'approval:a1',
        ref: 'a1',
        message: 'Supprimer l’événement « Psychomot »',
      })
    })

    test('interrupts over an open chat, which has no card for it', () => {
      const { result } = setup(true)

      stream().emit(approval())

      expect(result.current.current?.source).toBe('approval')
    })

    test('an expired one stays quiet', () => {
      const { result } = setup()

      stream().emit(approval({ expiresAt: '2020-01-01T00:00:00+00:00' }))

      expect(result.current.current).toBeNull()
    })

    test.each(['approved', 'denied', 'expired', 'failed'])('an approval %s elsewhere is withdrawn', (status) => {
      const { result } = setup()
      stream().emit(approval())
      stream().emit(approval({ id: 'a2' }))

      stream().emit(approval({ status }))

      expect(result.current.current?.ref).toBe('a2')
    })

    test('one answered elsewhere does not come back after « Plus tard »', () => {
      const { result } = setup()
      stream().emit(approval())
      act(() => result.current.dismiss(true))

      stream().emit(approval({ status: 'approved' }))
      act(() => vi.advanceTimersByTime(LATER_DELAY_MS))

      expect(result.current.current).toBeNull()
    })

    test('those already waiting when the admin opens interrupt', async () => {
      fetchPending.mockResolvedValue({ ok: true, json: async () => [approval(), approval({ id: 'a2' })] })

      const { result } = setup()
      await act(async () => {})

      expect(fetchPending).toHaveBeenCalledWith('/agent/approvals?status=pending', expect.any(Object))
      expect(result.current.current?.ref).toBe('a1')
      act(() => result.current.dismiss(false))
      expect(result.current.current?.ref).toBe('a2')
    })

    test('the same one arriving on the feed too interrupts once', async () => {
      fetchPending.mockResolvedValue({ ok: true, json: async () => [approval()] })
      const { result } = setup()
      await act(async () => {})

      stream().emit(approval())
      act(() => result.current.dismiss(false))

      expect(result.current.current).toBeNull()
    })

    test('a failing fetch of the waiting ones is not an interruption', async () => {
      fetchPending.mockRejectedValue(new Error('offline'))

      const { result } = setup()
      await act(async () => {})

      expect(result.current.current).toBeNull()
    })
  })
})
