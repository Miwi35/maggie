import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { renderWithTheme } from '../../test/renderWithTheme'
import { ChatWidget } from './ChatWidget'

/** The evening review's message carries a card that opens the activity (MAG-963). */

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

const bilan = {
  id: 'm-b',
  role: 'assistant',
  content: 'Bonsoir monsieur. Votre bilan de la journée vous attend.',
  createdAt: '2026-10-26T21:00:00Z',
  blocks: [{ type: 'bilan', day: '2026-10-26', route: 'maggie://activity/bilan' }],
}

const Review = () => {
  const location = useLocation()

  return <div data-testid="review" data-from={(location.state as { from?: string } | null)?.from}>{location.pathname}</div>
}

const renderChat = () =>
  renderWithTheme(
    <MemoryRouter initialEntries={['/finance?mois=10']}>
      <Routes>
        <Route path="/activites/bilan" element={<Review />} />
        <Route
          path="*"
          element={
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
            />
          }
        />
      </Routes>
    </MemoryRouter>,
  )

const stubHistory = (messages: unknown[]) =>
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(url.includes('/agent/messages') ? messages : []) }),
    ),
  )

describe('a bilan message in the chat', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', MockEventSource)
    MockEventSource.instances = []
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
  })
  afterEach(() => {
    localStorage.removeItem('user')
    vi.unstubAllGlobals()
  })

  test('a bilan message shows its sentence then its card', async () => {
    stubHistory([bilan])
    renderChat()
    await screen.findByText('Bonsoir monsieur. Votre bilan de la journée vous attend.')
    const card = screen.getByTestId('moment-card')
    expect(card).toHaveAttribute('data-message-id', 'm-b')
    expect(card).toHaveTextContent('Bilan de la journée')
    expect(card).toHaveTextContent('lun. 26 oct. · 5 étapes')
    expect(within(card).getByRole('link', { name: 'Ouvrir le bilan de la journée' })).toHaveAttribute('href', '/activites/bilan')
  })

  test('Ouvrir opens the review full screen, remembering where the chat was opened', async () => {
    stubHistory([bilan])
    renderChat()
    await screen.findByTestId('moment-card')
    fireEvent.click(screen.getByRole('link', { name: 'Ouvrir le bilan de la journée' }))
    const review = await screen.findByTestId('review')
    expect(review).toHaveTextContent('/activites/bilan')
    expect(review).toHaveAttribute('data-from', '/finance?mois=10')
  })

  test('the card comes over Mercure', async () => {
    stubHistory([{ id: 'm-1', role: 'user', content: 'Salut', createdAt: '2026-10-26T08:00:00Z' }])
    renderChat()
    await screen.findByText('Salut')
    const chat = MockEventSource.instances.find((es) =>
      new URL(es.url, 'http://x').searchParams.getAll('match').includes('/chat/user-1'),
    )!
    act(() => {
      chat.onmessage?.({ data: JSON.stringify(bilan) } as MessageEvent)
    })
    expect(await screen.findByTestId('moment-card')).toHaveAttribute('data-message-id', 'm-b')
  })

  test('a message without a bilan block has none', async () => {
    stubHistory([{ ...bilan, blocks: [] }])
    renderChat()
    await screen.findByText('Bonsoir monsieur. Votre bilan de la journée vous attend.')
    expect(screen.queryByTestId('moment-card')).not.toBeInTheDocument()
  })
})
