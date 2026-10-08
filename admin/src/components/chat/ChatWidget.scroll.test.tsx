import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import type { ComponentProps } from 'react'
import { ChatWidget } from './ChatWidget'
import { veilleuseDarkTheme } from '../../theme'

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

const stream = vi.hoisted(() => ({ callbacks: {} as Record<string, (...args: string[]) => void> }))
vi.mock('../../hooks/useAgUiStream', () => ({
  useAgUiStream: (callbacks: Record<string, (...args: string[]) => void>) => {
    stream.callbacks = callbacks
    return { send: vi.fn(), isStreaming: false }
  },
}))

const screen_ = vi.hoisted(() => ({ narrow: false }))
vi.mock('../../hooks/useNarrowScreen', () => ({ useNarrowScreen: () => screen_.narrow }))

class MockEventSource {
  close = vi.fn()
  onmessage: ((event: MessageEvent) => void) | null = null
}

const VIEWPORT = 500

type Props = ComponentProps<typeof ChatWidget>

const props: Props = {
  open: true,
  sidebarTab: 'chat',
  onTabChange: vi.fn(),
  onClose: vi.fn(),
  onUnread: vi.fn(),
  agentState: 'idle',
  onAgentStateChange: vi.fn(),
  contexts: [],
  onContextsChange: vi.fn(),
  toolCalls: [],
  onToolCallsChange: vi.fn(),
}

const thirtyMessages = Array.from({ length: 30 }, (_, i) => ({
  id: `msg-${i + 1}`,
  role: i % 2 === 0 ? 'user' : 'assistant',
  content: `Message ${String(i + 1).padStart(2, '0')}`,
  createdAt: `2026-01-05T09:${String(i + 1).padStart(2, '0')}:00Z`,
}))

/** The list's own element: the nearest ancestor of a bubble that scrolls. */
function scroller(): HTMLElement {
  let el = screen.getByText('Message 30').parentElement
  while (el && getComputedStyle(el).overflowY !== 'auto') el = el.parentElement
  if (!el) throw new Error('the message list is not mounted')
  return el
}

function atBottom(el: HTMLElement = scroller()) {
  return el.scrollTop >= el.scrollHeight - VIEWPORT
}

describe('ChatWidget scroll (MAG-348)', () => {
  const originalScrollHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollHeight')
  const originalClientHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight')

  beforeEach(() => {
    vi.stubGlobal('EventSource', MockEventSource)
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve(thirtyMessages) })),
    )
    // No layout in jsdom: a list is 100 px per child and 4 px per character taller.
    Object.defineProperty(HTMLElement.prototype, 'scrollHeight', {
      configurable: true,
      get(this: HTMLElement) {
        return this.childElementCount * 100 + (this.textContent?.length ?? 0) * 4
      },
    })
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => VIEWPORT })
    localStorage.removeItem('chat_lastReadMessageId')
    screen_.narrow = false
  })

  afterEach(() => {
    if (originalScrollHeight) Object.defineProperty(HTMLElement.prototype, 'scrollHeight', originalScrollHeight)
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollHeight')
    if (originalClientHeight) Object.defineProperty(HTMLElement.prototype, 'clientHeight', originalClientHeight)
    else Reflect.deleteProperty(HTMLElement.prototype, 'clientHeight')
  })

  function renderPanel() {
    const view = render(
      <ThemeProvider theme={veilleuseDarkTheme}>
        <ChatWidget {...props} />
      </ThemeProvider>,
    )
    const update = (next: Partial<Props>) =>
      view.rerender(
        <ThemeProvider theme={veilleuseDarkTheme}>
          <ChatWidget {...props} {...next} />
        </ThemeProvider>,
      )
    return { update }
  }

  async function opened() {
    const view = renderPanel()
    await screen.findByText('Message 30')
    return view
  }

  test('opens on the last message', async () => {
    await opened()

    expect(atBottom()).toBe(true)
  })

  test('closing and reopening the panel lands on the last message again', async () => {
    const { update } = await opened()
    update({ open: false })
    scroller().scrollTop = 0

    update({ open: true })

    expect(atBottom()).toBe(true)
  })

  test('on a narrow screen, where the drawer unmounts the list, reopening lands on the last message', async () => {
    screen_.narrow = true
    const { update } = await opened()
    update({ open: false })
    await waitFor(() => expect(screen.queryByText('Message 30')).not.toBeInTheDocument())

    update({ open: true })
    await screen.findByText('Message 30')

    expect(atBottom()).toBe(true)
  })

  test('jumping to a quoted message from the search does not pin the list to the bottom', async () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    const answer = (body: unknown) => Promise.resolve({ ok: true, json: () => Promise.resolve(body) })
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.includes('/messages/context')) return answer({ messages: thirtyMessages })
        if (url.includes('/messages/search')) return answer([thirtyMessages[4]])
        return answer(thirtyMessages)
      }),
    )
    const user = userEvent.setup()
    await opened()

    await user.click(screen.getByTestId('SearchIcon').closest('button')!)
    await user.type(screen.getByPlaceholderText('Rechercher...'), 'Message')
    await waitFor(() => expect(document.querySelector('mark')).not.toBeNull(), { timeout: 2000 })
    await user.click(document.querySelector('mark')!)

    await screen.findByText('Message 30')
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled())
    expect(atBottom()).toBe(false)
  })

  test('coming back from Mind lands on the last message', async () => {
    const { update } = await opened()

    update({ sidebarTab: 'mind' })
    update({ sidebarTab: 'chat' })

    expect(atBottom()).toBe(true)
  })

  test('follows an answer to its last line as it streams', async () => {
    await opened()

    act(() => stream.callbacks.onTextStart('answer'))
    for (let n = 1; n <= 20; n++) {
      act(() => stream.callbacks.onTextDelta('answer', `Ligne ${n} de la réponse\n`))
      expect(atBottom()).toBe(true)
    }
    act(() => stream.callbacks.onTextEnd('answer'))

    expect(atBottom()).toBe(true)
  })

  test('stops following once the user scrolls up, and the button brings the answer back', async () => {
    await opened()
    act(() => stream.callbacks.onTextStart('answer'))
    act(() => stream.callbacks.onTextDelta('answer', 'Début\n'))

    const list = scroller()
    list.scrollTop = list.scrollHeight - VIEWPORT - 600
    fireEvent.scroll(list)
    act(() => stream.callbacks.onTextDelta('answer', 'Suite de la réponse\n'))

    expect(atBottom()).toBe(false)
    const back = screen.getByText('Derniers messages')

    fireEvent.click(back)

    expect(atBottom()).toBe(true)
    await waitFor(() => expect(screen.queryByText('Derniers messages')).not.toBeInTheDocument())
  })

  test('a list that grows by itself, without the user, is not taken for a scroll up', async () => {
    await opened()
    act(() => stream.callbacks.onTextStart('answer'))

    const list = scroller()
    fireEvent.scroll(list)
    act(() => stream.callbacks.onTextDelta('answer', 'Une ligne de plus\n'))

    expect(screen.queryByText('Derniers messages')).not.toBeInTheDocument()
    expect(atBottom()).toBe(true)
  })
})
