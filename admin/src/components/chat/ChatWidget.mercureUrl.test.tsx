import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'

/**
 * Regression test for b16916d, found a second time by the Playwright socle
 * (MAG-97).
 *
 * `VITE_MERCURE_PUBLIC_URL` is relative in production — `/.well-known/mercure`
 * — and `new URL()` throws on a relative string with no base. `useMercure` was
 * fixed for it; `ChatWidget` was not, so the chat panel's two subscriptions
 * died everywhere the variable was relative, which is everywhere that matters.
 *
 * The existing ChatWidget suite could not catch it: with the variable unset,
 * the component falls back to an absolute `http://maggie.local/...`, and the
 * constructor is happy. So this file sets the production shape before the
 * module is loaded — MERCURE_URL is read once, at module scope.
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

const subscriptions: string[] = []

class RecordingEventSource {
  close = vi.fn()
  onmessage: ((event: MessageEvent) => void) | null = null

  constructor(public url: string) {
    subscriptions.push(url)
  }
}

const defaultProps = {
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

describe('ChatWidget with a relative Mercure URL', () => {
  beforeEach(() => {
    subscriptions.length = 0
    vi.stubGlobal('EventSource', RecordingEventSource)
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve([]) })),
    )
    localStorage.setItem('user', JSON.stringify({ id: 'user-1', name: 'Camille' }))
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('resolves it against the current origin instead of throwing', async () => {
    vi.stubEnv('VITE_MERCURE_PUBLIC_URL', '/.well-known/mercure')
    vi.resetModules()

    const { ChatWidget } = await import('./ChatWidget')
    render(<ChatWidget {...defaultProps} />)

    await waitFor(() => expect(subscriptions.length).toBeGreaterThan(0))

    for (const url of subscriptions) {
      expect(url.startsWith(`${window.location.origin}/.well-known/mercure`)).toBe(true)
    }
  })

  test('subscribes to the chat and context topics of the signed-in user', async () => {
    vi.stubEnv('VITE_MERCURE_PUBLIC_URL', '/.well-known/mercure')
    vi.resetModules()

    const { ChatWidget } = await import('./ChatWidget')
    render(<ChatWidget {...defaultProps} />)

    await waitFor(() => expect(subscriptions.length).toBeGreaterThanOrEqual(2))

    // Mercure 1.0: an exact topic is `match`; the 0.x `topic` is refused with a 400.
    const exact = subscriptions.flatMap((url) => new URL(url).searchParams.getAll('match'))
    expect(exact).toContain('/chat/user-1')
    expect(exact).toContain('/contexts/user-1')
    for (const url of subscriptions) {
      expect(new URL(url).searchParams.has('topic')).toBe(false)
    }
  })
})
