import { describe, test, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}
vi.stubGlobal('EventSource', MockEventSource)

const defaultProps = {
  open: true,
  onClose: vi.fn(),
  onUnread: vi.fn(),
  agentState: 'idle' as const,
  onAgentStateChange: vi.fn(),
  onContextsChange: vi.fn(),
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
    mockSend.mockReset()
    defaultProps.onClose = vi.fn()
    defaultProps.onUnread = vi.fn()
    defaultProps.onAgentStateChange = vi.fn()
    defaultProps.onContextsChange = vi.fn()
    defaultProps.onToolCallsChange = vi.fn()
    localStorage.removeItem('chat_lastReadMessageId')
  })

  test('renders header and input when open', () => {
    vi.stubGlobal('fetch', mockFetch({ '/agent/messages': [] }))
    render(<ChatWidget {...defaultProps} />)

    expect(screen.getByText('Maggie')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Demande à Maggie...')).toBeInTheDocument()
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
