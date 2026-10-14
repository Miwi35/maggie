import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithTheme } from '../../test/renderWithTheme'
import { ChatWidget } from './ChatWidget'

/**
 * A thumb up or down under Maggie's answers (MAG-23).
 *
 * Only her answers carry one; the thumb shows at once and goes back to what it was when the
 * server refuses it; a thumb down offers an optional comment; tapping the chosen thumb again
 * takes it off.
 */

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

class SilentEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
}

const MESSAGES = [
  { id: 'm-1', role: 'user', content: 'Quel temps demain ?', createdAt: '2026-10-05T10:00:00Z' },
  { id: 'm-2', role: 'assistant', content: 'Beau temps.', createdAt: '2026-10-05T10:00:05Z', feedback: null },
  { id: 'm-3', role: 'assistant', content: 'Et doux.', createdAt: '2026-10-05T10:00:06Z', feedback: 'up' },
]

interface Call {
  method: string
  url: string
  body?: unknown
}

let calls: Call[]

function stubApi({ feedbackStatus = 200 }: { feedbackStatus?: number } = {}) {
  calls = []
  const reply = (status: number, body: unknown) =>
    Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) })
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (url.includes('/feedback')) return reply(feedbackStatus, {})
      if (url.includes('/agent/messages')) return reply(200, MESSAGES)
      if (url.includes('/agent/contexts')) return reply(200, [])
      return reply(404, {})
    }),
  )
}

const feedbackCalls = () => calls.filter((c) => c.url.includes('/feedback'))
const noop = vi.fn()

function renderChat() {
  return renderWithTheme(
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
}

/** The thumbs right under one answer. */
async function thumbsOf(text: string) {
  const bubble = (await screen.findByText(text)).closest('[data-role]') as HTMLElement
  let row: Element | null = bubble
  while (row && !row.nextElementSibling?.querySelector('[aria-label="Réponse utile"]')) row = row.parentElement
  return within(row!.nextElementSibling as HTMLElement)
}

describe('thumbs on the answers', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', SilentEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    stubApi()
  })

  afterEach(() => {
    localStorage.removeItem('user')
    vi.unstubAllGlobals()
  })

  test('only the answers carry thumbs, and the stored one shows as chosen', async () => {
    renderChat()
    await screen.findByText('Et doux.')

    expect(screen.getAllByRole('button', { name: 'Réponse utile' })).toHaveLength(2)
    const [first, second] = screen.getAllByRole('button', { name: 'Réponse utile' })
    expect(first).toHaveAttribute('aria-pressed', 'false')
    expect(second).toHaveAttribute('aria-pressed', 'true')
  })

  test('a thumb up is sent and shows at once', async () => {
    const user = userEvent.setup()
    renderChat()
    const thumbs = await thumbsOf('Beau temps.')

    await user.click(thumbs.getByRole('button', { name: 'Réponse utile' }))

    expect(thumbs.getByRole('button', { name: 'Réponse utile' })).toHaveAttribute('aria-pressed', 'true')
    expect(feedbackCalls()).toEqual([{ method: 'PUT', url: '/agent/messages/m-2/feedback', body: { rating: 'up' } }])
  })

  test('a thumb down offers a comment, sent with it', async () => {
    const user = userEvent.setup()
    renderChat()
    const thumbs = await thumbsOf('Beau temps.')

    await user.click(thumbs.getByRole('button', { name: 'Réponse à revoir' }))
    await user.type(screen.getByRole('textbox', { name: 'Commentaire sur la réponse' }), 'Trop vague')
    await user.click(within(screen.getByRole('textbox', { name: 'Commentaire sur la réponse' }).closest('form')!).getByRole('button', { name: 'Envoyer' }))

    expect(feedbackCalls().map((c) => c.body)).toEqual([{ rating: 'down' }, { rating: 'down', comment: 'Trop vague' }])
    expect(screen.queryByRole('textbox', { name: 'Commentaire sur la réponse' })).not.toBeInTheDocument()
  })

  test('tapping the chosen thumb again takes it off', async () => {
    const user = userEvent.setup()
    renderChat()
    const thumbs = await thumbsOf('Et doux.')

    await user.click(thumbs.getByRole('button', { name: 'Réponse utile' }))

    expect(feedbackCalls()).toEqual([{ method: 'DELETE', url: '/agent/messages/m-3/feedback', body: undefined }])
    expect(thumbs.getByRole('button', { name: 'Réponse utile' })).toHaveAttribute('aria-pressed', 'false')
  })

  test('a refused thumb goes back to what it was, and says so', async () => {
    stubApi({ feedbackStatus: 500 })
    const user = userEvent.setup()
    vi.spyOn(console, 'error').mockImplementation(() => {})
    renderChat()
    const thumbs = await thumbsOf('Et doux.')

    await user.click(thumbs.getByRole('button', { name: 'Réponse à revoir' }))

    await waitFor(() =>
      expect(screen.getByText("Votre retour n'a pas pu être enregistré. Réessayez.")).toBeInTheDocument(),
    )
    expect(thumbs.getByRole('button', { name: 'Réponse utile' })).toHaveAttribute('aria-pressed', 'true')
    expect(thumbs.getByRole('button', { name: 'Réponse à revoir' })).toHaveAttribute('aria-pressed', 'false')
  })
})
