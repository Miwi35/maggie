import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithTheme } from '../../test/renderWithTheme'
import userEvent from '@testing-library/user-event'
import { ChatWidget } from './ChatWidget'
import { PHONE_WIDTH, TABLET_WIDTH, DESKTOP_WIDTH, setViewportWidth, resetViewport } from '../../test/viewport'

/**
 * The conversation is a column beside the page on a desk and a sheet over it
 * below `md` (MAG-38).
 *
 * 380px of chat next to a 393px phone leaves nothing to talk *about*, and at
 * 834px it took a third of the window on top of the menu's own. So below the
 * breakpoint the panel is a drawer, and `Layout` arrives with it shut — the
 * app bar's "Chat avec Maggie" is what opens it.
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

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}
vi.stubGlobal('EventSource', MockEventSource)

const onClose = vi.fn()

const props = {
  sidebarTab: 'chat' as const,
  onTabChange: vi.fn(),
  onClose,
  onUnread: vi.fn(),
  agentState: 'idle' as const,
  onAgentStateChange: vi.fn(),
  contexts: [],
  onContextsChange: vi.fn(),
  toolCalls: [],
  onToolCallsChange: vi.fn(),
}

const input = () => screen.queryByPlaceholderText('Demande à Maggie...')

describe('ChatWidget across widths', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) }))
    onClose.mockReset()
  })

  afterEach(() => {
    resetViewport()
  })

  test.each([
    ['a phone', PHONE_WIDTH],
    ['a tablet', TABLET_WIDTH],
  ])('shows nothing of the conversation while it is shut on %s', (_label, width) => {
    setViewportWidth(width)

    renderWithTheme(<ChatWidget {...props} open={false} />)

    expect(input()).toBeNull()
    expect(screen.queryByTestId('chat-panel')).toBeNull()
  })

  test.each([
    ['a phone', PHONE_WIDTH],
    ['a tablet', TABLET_WIDTH],
  ])('opens over the whole window on %s', (_label, width) => {
    setViewportWidth(width)

    renderWithTheme(<ChatWidget {...props} open />)

    const panel = screen.getByTestId('chat-panel')
    expect(input()).toBeVisible()
    // A sheet, not a column: the panel is inside a modal, and its paper has
    // no 380px of its own to leave the page squeezed beside it.
    expect(panel.closest('.MuiModal-root')).not.toBeNull()
    expect(panel.closest('.MuiDrawer-paper')).toHaveStyle({ width: '100%' })
  })

  test('closes on Escape, which is the sheet’s way out', async () => {
    setViewportWidth(PHONE_WIDTH)
    const user = userEvent.setup()

    renderWithTheme(<ChatWidget {...props} open />)
    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalled()
  })

  test('stays the column beside the page on a desk', () => {
    setViewportWidth(DESKTOP_WIDTH)

    renderWithTheme(<ChatWidget {...props} open />)

    expect(screen.getByTestId('chat-panel').closest('.MuiModal-root')).toBeNull()
    expect(input()).toBeVisible()
  })
})
