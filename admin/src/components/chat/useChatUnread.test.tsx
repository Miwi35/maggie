import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useChatUnread } from './useChatUnread'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onmessage: ((event: { data: string }) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    FakeEventSource.instances.push(this)
  }
}

const json = (body: unknown) => Promise.resolve({ ok: true, json: () => Promise.resolve(body) })

describe('useChatUnread', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    FakeEventSource.instances = []
    fetchMock.mockReset()
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.removeItem('user')
  })

  test('loads the read position and the count from the server', async () => {
    fetchMock.mockReturnValue(json({ lastReadMessageId: 'm1', unreadCount: 2 }))
    const { result } = renderHook(() => useChatUnread())

    expect(result.current.loaded).toBe(false)
    await waitFor(() => expect(result.current.loaded).toBe(true))
    expect(fetchMock.mock.calls[0][0]).toBe('/agent/chat/unread')
    expect(result.current).toMatchObject({ lastReadMessageId: 'm1', unreadCount: 2 })
  })

  test('follows the chat-read topic', async () => {
    fetchMock.mockReturnValue(json({ lastReadMessageId: null, unreadCount: 0 }))
    const { result } = renderHook(() => useChatUnread())
    await waitFor(() => expect(result.current.loaded).toBe(true))

    const topics = new URL(FakeEventSource.instances[0].url, 'http://localhost').searchParams.getAll('match')
    expect(topics).toContain('/chat-read/user-1')

    act(() => FakeEventSource.instances[0].onmessage?.({ data: JSON.stringify({ lastReadMessageId: 'm9', unreadCount: 4 }) }))
    expect(result.current).toMatchObject({ lastReadMessageId: 'm9', unreadCount: 4 })

    // Another payload of the shared feed is not ours.
    act(() => FakeEventSource.instances[0].onmessage?.({ data: JSON.stringify({ id: 'c1', label: 'Courses' }) }))
    expect(result.current.unreadCount).toBe(4)
  })

  test('markRead puts the position on the server once per message and takes its answer', async () => {
    fetchMock.mockImplementation((url: string) =>
      url === '/agent/chat/read' ? json({ lastReadMessageId: 'm3', unreadCount: 0 }) : json({ lastReadMessageId: 'm1', unreadCount: 2 }),
    )
    const { result } = renderHook(() => useChatUnread())
    await waitFor(() => expect(result.current.loaded).toBe(true))

    act(() => {
      result.current.markRead('m3')
      result.current.markRead('m3')
    })

    await waitFor(() => expect(result.current).toMatchObject({ lastReadMessageId: 'm3', unreadCount: 0 }))
    const puts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(puts).toHaveLength(1)
    expect(puts[0][0]).toBe('/agent/chat/read')
    expect(JSON.parse(puts[0][1].body)).toEqual({ messageId: 'm3' })
  })

  test('a failing read leaves the badge hidden', async () => {
    fetchMock.mockRejectedValue(new Error('down'))
    const { result } = renderHook(() => useChatUnread())
    await act(async () => {})
    expect(result.current).toMatchObject({ loaded: false, unreadCount: 0 })
  })
})
