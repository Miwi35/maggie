import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, screen, within } from '@testing-library/react'
import { renderWithTheme } from '../../test/renderWithTheme'
import { ChatWidget } from './ChatWidget'

/** A voice message reaches the thread over Mercure, without a reload (MAG-686). */

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
  static instances: MockEventSource[] = []
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    MockEventSource.instances.push(this)
  }
}

const noop = vi.fn()

describe('a voice message in the chat', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', MockEventSource)
    MockEventSource.instances = []
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve(
              url.includes('/agent/messages')
                ? [{ id: 'm-1', role: 'user', content: 'Un vocal ?', createdAt: '2026-10-29T08:00:00Z' }]
                : [],
            ),
        }),
      ),
    )
  })
  afterEach(() => {
    localStorage.removeItem('user')
    vi.unstubAllGlobals()
  })

  test('shows its player when it arrives, the rest of the thread untouched', async () => {
    renderWithTheme(
      <ChatWidget
        open
        sidebarTab="chat"
        onTabChange={noop}
        onClose={noop}
        onUnread={noop}
        agentState="idle"
        onAgentStateChange={noop}
        contexts={[]}
        onContextsChange={noop}
        toolCalls={[]}
        onToolCallsChange={noop}
      />,
    )
    await screen.findByText('Un vocal ?')
    expect(screen.queryByTestId('voice-bubble')).not.toBeInTheDocument()

    const chat = MockEventSource.instances.find((es) =>
      new URL(es.url, 'http://x').searchParams.getAll('match').includes('/chat/user-1'),
    )!
    act(() => {
      chat.onmessage?.({
        data: JSON.stringify({
          id: 'm-2',
          role: 'assistant',
          content: '[warmly] Ta journée.',
          createdAt: '2026-10-29T08:01:00Z',
          audio: {
            type: 'audio/mpeg',
            durationMs: 5000,
            size: 10,
            url: '/agent/messages/m-2/audio',
            transcript: 'Ta journée.',
          },
        }),
      } as MessageEvent)
    })

    expect(await screen.findByTestId('voice-bubble')).toBeInTheDocument()
    expect(screen.getByTestId('voice-duration')).toHaveTextContent('0:05')
    expect(screen.getByText('Un vocal ?')).toBeInTheDocument()
  })

  const renderChat = () =>
    renderWithTheme(
      <ChatWidget
        open
        sidebarTab="chat"
        onTabChange={noop}
        onClose={noop}
        onUnread={noop}
        agentState="idle"
        onAgentStateChange={noop}
        contexts={[]}
        onContextsChange={noop}
        toolCalls={[]}
        onToolCallsChange={noop}
      />,
    )

  const digest = {
    id: 'm-d',
    role: 'assistant',
    content: 'Votre digest du matin est disponible.',
    createdAt: '2026-11-21T07:00:00Z',
    audio: { type: 'audio/mpeg', durationMs: 5000, size: 10, url: '/agent/messages/m-d/audio', transcript: 'Il fait beau.' },
    ui_blocks: [{ type: 'digest' }],
  }

  const stubHistory = (messages: unknown[]) =>
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(url.includes('/agent/messages') ? messages : []) }),
      ),
    )

  test('a digest message shows its announcement above its voice bubble', async () => {
    stubHistory([digest])
    renderChat()
    const bubble = await screen.findByTestId('voice-bubble')
    const message = bubble.closest('#msg-m-d') as HTMLElement
    expect(message).not.toBeNull()
    expect(within(message).getByText('Votre digest du matin est disponible.')).toBeInTheDocument()
  })

  test('the same announcement arriving over Mercure shows text and bubble', async () => {
    renderChat()
    await screen.findByText('Un vocal ?')
    const chat = MockEventSource.instances.find((es) =>
      new URL(es.url, 'http://x').searchParams.getAll('match').includes('/chat/user-1'),
    )!
    act(() => {
      chat.onmessage?.({ data: JSON.stringify(digest) } as MessageEvent)
    })
    const bubble = await screen.findByTestId('voice-bubble')
    const message = bubble.closest('#msg-m-d') as HTMLElement
    expect(within(message).getByText('Votre digest du matin est disponible.')).toBeInTheDocument()
  })

  test('a voice message whose text is its transcript shows the bubble alone', async () => {
    stubHistory([{ ...digest, id: 'm-v', content: '[warmly] Ta journée.', audio: { ...digest.audio, transcript: 'Ta journée.' } }])
    renderChat()
    await screen.findByTestId('voice-bubble')
    expect(screen.queryByText('Ta journée.')).not.toBeInTheDocument()
  })
})
