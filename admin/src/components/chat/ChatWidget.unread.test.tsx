import { beforeEach, describe, expect, test, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { renderWithTheme } from '../../test/renderWithTheme'
import { ChatWidget } from './ChatWidget'

vi.mock('../../hooks/useVoiceRecorder', () => ({
  useVoiceRecorder: () => ({
    state: 'idle' as const,
    duration: 0,
    error: null,
    startRecording: vi.fn(),
    stopRecording: vi.fn(),
    cancelRecording: vi.fn(),
    resetState: vi.fn(),
  }),
}))
vi.mock('../../hooks/useTranscription', () => ({
  useTranscription: () => ({ transcribe: vi.fn(), loading: false, error: null }),
}))
vi.mock('../../hooks/useAgUiStream', () => ({
  useAgUiStream: () => ({ send: vi.fn(), isStreaming: false }),
}))

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(
    public url: string,
    public init?: EventSourceInit,
  ) {}
}

const history = [
  { id: 'm1', role: 'user', content: 'Bonjour', createdAt: '2026-01-10T10:00:00Z' },
  { id: 'm2', role: 'assistant', content: 'Salut', createdAt: '2026-01-10T10:05:00Z' },
]
const around = [...history, { id: 'm3', role: 'assistant', content: 'Le message de la notification', createdAt: '2026-01-10T10:10:00Z' }]

const props = {
  open: true,
  sidebarTab: 'chat' as const,
  onTabChange: vi.fn(),
  onClose: vi.fn(),
  onUnread: vi.fn(),
  agentState: 'idle' as const,
  onAgentStateChange: vi.fn(),
  contexts: [],
  onContextsChange: vi.fn(),
  toolCalls: [],
  onToolCallsChange: vi.fn(),
}

const reads = (fetchMock: ReturnType<typeof vi.fn>) =>
  fetchMock.mock.calls.filter(([url, init]) => String(url) === '/agent/chat/read' && init?.method === 'PUT')

function stubFetch(unread = { lastReadMessageId: 'm1', unreadCount: 1 }) {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    const ok = (body: unknown) => Promise.resolve({ ok: true, json: () => Promise.resolve(body) })
    if (url.includes('/agent/chat/unread')) return ok(unread)
    if (url.includes('/agent/chat/read') && init?.method === 'PUT') return ok({ lastReadMessageId: 'm2', unreadCount: 0 })
    if (url.includes('/agent/messages/context')) return ok({ messages: around })
    if (url.includes('/agent/messages')) return ok(history)
    return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('ChatWidget — read position on the server (MAG-687)', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
  })

  test('marks the last message read when the chat is open and followed', async () => {
    const fetchMock = stubFetch()
    renderWithTheme(<ChatWidget {...props} />)

    await waitFor(() => expect(reads(fetchMock)).toHaveLength(1))
    expect(JSON.parse(reads(fetchMock)[0][1].body)).toEqual({ messageId: 'm2' })
    expect(localStorage.getItem('chat_lastReadMessageId')).toBeNull()
  })

  test('marks nothing read while the chat is closed', async () => {
    const fetchMock = stubFetch()
    renderWithTheme(<ChatWidget {...props} open={false} />)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/agent/chat/unread', expect.anything()))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(reads(fetchMock)).toHaveLength(0)
  })

  test('hands the count from the server to the layout', async () => {
    stubFetch({ lastReadMessageId: 'm1', unreadCount: 3 })
    const onUnreadCount = vi.fn()
    renderWithTheme(<ChatWidget {...props} open={false} onUnreadCount={onUnreadCount} />)

    await waitFor(() => expect(onUnreadCount).toHaveBeenCalledWith(3))
  })

  test('opens on the message a notification points at, through the around endpoint', async () => {
    const fetchMock = stubFetch()
    const onMessageOpened = vi.fn()
    renderWithTheme(<ChatWidget {...props} openMessageId="m3" onMessageOpened={onMessageOpened} />)

    expect(await screen.findByText('Le message de la notification')).toBeInTheDocument()
    expect(onMessageOpened).toHaveBeenCalled()
    const contextCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/agent/messages/context'))
    expect(new URL(String(contextCall?.[0]), 'http://localhost').searchParams.get('around')).toBe('m3')
  })
})
