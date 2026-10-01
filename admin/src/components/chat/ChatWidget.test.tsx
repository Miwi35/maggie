import { describe, test, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ChatWidget } from './ChatWidget'

// Mock voice hooks to avoid MediaRecorder issues in tests
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
  useTranscription: () => ({
    transcribe: vi.fn(),
    loading: false,
    error: null,
  }),
}))

const mockSend = vi.fn()
vi.mock('../../hooks/useAgUiStream', () => ({
  useAgUiStream: () => ({
    send: mockSend,
    isStreaming: false,
  }),
}))

// Mock EventSource globally before any render
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
}
vi.stubGlobal('EventSource', MockEventSource)

const defaultProps = {
  open: true,
  sidebarTab: 'chat' as const,
  onTabChange: vi.fn(),
  onClose: vi.fn(),
  onUnread: vi.fn(),
  agentState: 'idle' as const,
  onAgentStateChange: vi.fn(),
  contexts: [] as { id: string; label: string; status: 'active' | 'dormant' | 'closed' }[],
  onContextsChange: vi.fn(),
  toolCalls: [] as { toolCallId: string; toolName: string; status: 'running' | 'success' | 'error' }[],
  onToolCallsChange: vi.fn(),
}

function mockFetch(responses: Record<string, unknown>) {
  // Sort patterns longest-first so /messages/search matches before /messages
  const sorted = Object.entries(responses).sort(([a], [b]) => b.length - a.length)
  return vi.fn((url: string) => {
    for (const [pattern, data] of sorted) {
      if (url.includes(pattern)) {
        return Promise.resolve({
          ok: true,
          json: () => Promise.resolve(data),
        })
      }
    }
    return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) })
  }) as Mock
}

describe('ChatWidget', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    MockEventSource.instances = []
    mockSend.mockReset()
    defaultProps.onTabChange = vi.fn()
    defaultProps.onClose = vi.fn()
    defaultProps.onUnread = vi.fn()
    defaultProps.onAgentStateChange = vi.fn()
    defaultProps.onContextsChange = vi.fn()
    defaultProps.onToolCallsChange = vi.fn()
    localStorage.removeItem('chat_lastReadMessageId')
  })

  test('renders tabs and input when open', () => {
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))
    render(<ChatWidget {...defaultProps} />)

    expect(screen.getByRole('tab', { name: /Chat/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Mind/i })).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Demande à Maggie...')).toBeInTheDocument()
  })

  // Both carry an icon and a tooltip, and a tooltip is not an accessible name —
  // MUI renders it in a portal. Without these labels neither button is
  // reachable by a screen reader or by the chat journey (MAG-99).
  test('names the dictation and send buttons', () => {
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))
    render(<ChatWidget {...defaultProps} />)

    expect(screen.getByRole('button', { name: 'Dicter' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Envoyer' })).toBeInTheDocument()
  })

  test('subscribes to the agent topics of the current user, with credentials', () => {
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))

    try {
      render(<ChatWidget {...defaultProps} />)
    } finally {
      localStorage.removeItem('user')
    }

    // Private updates reach only requests carrying the mercureAuthorization
    // cookie, and the agent publishes /chat/{id} and /contexts/{id} (MAG-139).
    const topics = MockEventSource.instances.map((es) => new URL(es.url, 'http://localhost').searchParams.get('topic'))
    expect(topics).toEqual(expect.arrayContaining(['/chat/user-1', '/contexts/user-1']))
    for (const es of MockEventSource.instances) {
      expect(es.init?.withCredentials).toBe(true)
    }
  })

  test('opens no subscription when nobody is signed in, instead of one on "default"', () => {
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))
    localStorage.removeItem('user')

    render(<ChatWidget {...defaultProps} />)

    expect(MockEventSource.instances).toEqual([])
  })

  // A proaction is persisted and published by the agent on /chat/{id}; the
  // panel holds no temp message for it, so the Mercure echo is the only way
  // it reaches the screen (MAG-109).
  describe('messages published on the chat topic', () => {
    function chatSource(): MockEventSource {
      const source = MockEventSource.instances.find(
        (es) => new URL(es.url, 'http://localhost').searchParams.get('topic') === '/chat/user-1',
      )
      if (!source) throw new Error('the panel is not subscribed to /chat/user-1')
      return source
    }

    function publish(source: MockEventSource, payload: Record<string, unknown>) {
      act(() => {
        source.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent)
      })
    }

    beforeEach(() => {
      localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    })

    afterEach(() => {
      localStorage.removeItem('user')
    })

    test('flags a proactive message as unread when the panel is closed', async () => {
      vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))
      render(<ChatWidget {...defaultProps} open={false} />)

      const message = {
        id: 'proaction-1',
        role: 'assistant',
        content: 'Petit rappel : les poubelles sortent ce soir.',
        createdAt: '2026-10-01T18:00:00Z',
      }
      publish(chatSource(), message)
      publish(chatSource(), message)

      expect(defaultProps.onUnread).toHaveBeenCalled()
    })

    test('appends a proactive message to an open chat, without duplicating a replay', async () => {
      vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))
      render(<ChatWidget {...defaultProps} />)

      const message = {
        id: 'proaction-1',
        role: 'assistant',
        content: 'Petit rappel : les poubelles sortent ce soir.',
        createdAt: '2026-10-01T18:00:00Z',
      }
      publish(chatSource(), message)
      publish(chatSource(), message)

      await waitFor(() => {
        expect(screen.getAllByText(message.content)).toHaveLength(1)
      })
      expect(defaultProps.onUnread).not.toHaveBeenCalled()
    })
  })

  describe('a thread summary', () => {
    function contextSource(): MockEventSource {
      const source = MockEventSource.instances.find(
        (es) => new URL(es.url, 'http://localhost').searchParams.get('topic') === '/contexts/user-1',
      )
      if (!source) throw new Error('the panel is not subscribed to /contexts/user-1')
      return source
    }

    beforeEach(() => {
      localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    })

    afterEach(() => {
      localStorage.removeItem('user')
    })

    test('comes with the contexts loaded on mount', async () => {
      vi.stubGlobal(
        'fetch',
        mockFetch({
          '/agent/messages': [],
          '/agent/contexts': [
            {
              id: 'ctx-1',
              label: 'Courses de la semaine',
              status: 'active',
              summary: 'Deux kilos de farine à acheter.',
            },
          ],
        }),
      )

      render(<ChatWidget {...defaultProps} />)

      await waitFor(() => {
        expect(defaultProps.onContextsChange).toHaveBeenCalledWith([
          {
            id: 'ctx-1',
            label: 'Courses de la semaine',
            status: 'active',
            summary: 'Deux kilos de farine à acheter.',
          },
        ])
      })
    })

    // A summary is written in the background, after the stream the owner was
    // watching has closed — so this is the only path that ever brings one to a panel
    // that is already open (MAG-11).
    test('reaches an open panel over Mercure', async () => {
      vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [], '/agent/contexts': [] }))
      render(<ChatWidget {...defaultProps} />)

      act(() => {
        contextSource().onmessage?.({
          data: JSON.stringify({
            id: 'ctx-1',
            label: 'Courses de la semaine',
            status: 'active',
            summary: 'Deux kilos de farine à acheter.',
          }),
        } as MessageEvent)
      })

      await waitFor(() => {
        expect(defaultProps.onContextsChange).toHaveBeenCalledWith([
          expect.objectContaining({ summary: 'Deux kilos de farine à acheter.' }),
        ])
      })
    })
  })

  test('loads history on open', async () => {
    const historyMessages = [
      { id: 'msg-1', role: 'user', content: 'Hello', createdAt: '2026-01-01T10:00:00Z' },
      { id: 'msg-2', role: 'assistant', content: 'Hi there!', createdAt: '2026-01-01T10:00:01Z' },
    ]
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': historyMessages }))

    render(<ChatWidget {...defaultProps} />)

    await waitFor(() => {
      expect(screen.getByText('Hello')).toBeInTheDocument()
      expect(screen.getByText('Hi there!')).toBeInTheDocument()
    })
  })

  test('sends message via streaming', async () => {
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))

    const user = userEvent.setup()
    render(<ChatWidget {...defaultProps} />)

    const input = screen.getByPlaceholderText('Demande à Maggie...')
    await user.type(input, 'Hello Maggie')

    const sendButton = screen.getByTestId('SendIcon').closest('button')!
    await user.click(sendButton)

    // Verify the streaming send was called
    await waitFor(() => {
      expect(mockSend).toHaveBeenCalledWith('Hello Maggie')
    })

    // Verify optimistic user message appears
    expect(screen.getByText('Hello Maggie')).toBeInTheDocument()
  })

  test('does not duplicate messages with same ID', async () => {
    const historyMessages = [
      { id: 'msg-1', role: 'assistant', content: 'Unique message', createdAt: '2026-01-01T10:00:00Z' },
    ]
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': historyMessages }))

    render(<ChatWidget {...defaultProps} />)

    await waitFor(() => {
      expect(screen.getByText('Unique message')).toBeInTheDocument()
    })

    // Only one instance should exist
    const matches = screen.getAllByText('Unique message')
    expect(matches).toHaveLength(1)
  })

  test('shows search input when search icon clicked', async () => {
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))
    const user = userEvent.setup()
    render(<ChatWidget {...defaultProps} />)

    const searchButton = screen.getByTestId('SearchIcon').closest('button')!
    await user.click(searchButton)

    expect(screen.getByPlaceholderText('Rechercher...')).toBeInTheDocument()
  })

  test('search calls API and shows results', async () => {
    const searchResults = [
      { id: 'msg-5', role: 'assistant', content: 'Found this answer', createdAt: '2026-01-01T10:00:00Z' },
    ]
    vi.stubGlobal(
      'fetch',
      mockFetch({
        '/agent/messages/search': searchResults,
        '/agent/messages': [],
      }),
    )

    const user = userEvent.setup()
    render(<ChatWidget {...defaultProps} />)

    // Open search
    const searchButton = screen.getByTestId('SearchIcon').closest('button')!
    await user.click(searchButton)

    // Type search query
    const searchInput = screen.getByPlaceholderText('Rechercher...')
    await user.type(searchInput, 'answer')

    // Wait for debounced search and results
    await waitFor(
      () => {
        // dangerouslySetInnerHTML splits text across <mark> nodes, so check textContent
        const container = document.querySelector('[class*="MuiBox-root"]')
        expect(container?.textContent).toContain('Found this')
      },
      { timeout: 1000 },
    )
  })

  test('shows date separator between messages on different days', async () => {
    const historyMessages = [
      { id: 'msg-1', role: 'user', content: 'First day message', createdAt: '2026-01-10T10:00:00Z' },
      { id: 'msg-2', role: 'assistant', content: 'Second day message', createdAt: '2026-01-11T14:30:00Z' },
    ]
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': historyMessages }))

    render(<ChatWidget {...defaultProps} />)

    await waitFor(() => {
      expect(screen.getByText('First day message')).toBeInTheDocument()
      expect(screen.getByText('Second day message')).toBeInTheDocument()
    })

    // Both messages should have date separators (first message always gets one, second is a different day)
    const captions = document.querySelectorAll('.MuiTypography-caption')
    expect(captions.length).toBeGreaterThanOrEqual(2)
  })

  test('shows unread breakline when lastReadMessageId is set in localStorage', async () => {
    localStorage.setItem('chat_lastReadMessageId', 'msg-1')

    const historyMessages = [
      { id: 'msg-1', role: 'user', content: 'Read message', createdAt: '2026-01-10T10:00:00Z' },
      { id: 'msg-2', role: 'assistant', content: 'Unread message', createdAt: '2026-01-10T10:05:00Z' },
    ]
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': historyMessages }))

    render(<ChatWidget {...defaultProps} />)

    await waitFor(() => {
      expect(screen.getByText('Messages non lus')).toBeInTheDocument()
    })

    // Both messages should still render
    expect(screen.getByText('Read message')).toBeInTheDocument()
    expect(screen.getByText('Unread message')).toBeInTheDocument()
  })

  test('does not show unread breakline when lastReadMessageId is last message', async () => {
    localStorage.setItem('chat_lastReadMessageId', 'msg-2')

    const historyMessages = [
      { id: 'msg-1', role: 'user', content: 'Old message', createdAt: '2026-01-10T10:00:00Z' },
      { id: 'msg-2', role: 'assistant', content: 'Last message', createdAt: '2026-01-10T10:05:00Z' },
    ]
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': historyMessages }))

    render(<ChatWidget {...defaultProps} />)

    await waitFor(() => {
      expect(screen.getByText('Last message')).toBeInTheDocument()
    })

    expect(screen.queryByText('Messages non lus')).not.toBeInTheDocument()
  })

  test('shows empty state for no search results', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch({
        '/agent/messages/search': [],
        '/agent/messages': [],
      }),
    )

    const user = userEvent.setup()
    render(<ChatWidget {...defaultProps} />)

    // Open search and type
    const searchButton = screen.getByTestId('SearchIcon').closest('button')!
    await user.click(searchButton)

    const searchInput = screen.getByPlaceholderText('Rechercher...')
    await user.type(searchInput, 'nonexistent')

    await waitFor(
      () => {
        expect(screen.getByText('Aucun message trouvé')).toBeInTheDocument()
      },
      { timeout: 1000 },
    )
  })
})
