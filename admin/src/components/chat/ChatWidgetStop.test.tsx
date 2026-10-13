import { useState } from 'react'
import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithTheme } from '../../test/renderWithTheme'
import { ChatWidget } from './ChatWidget'
import type { ContextState } from '../mind/types'

/**
 * Stopping Maggie and correcting the message, as on Claude (MAG-320).
 *
 * « Arrêter » replaces « Envoyer » while she answers; it closes the stream, tells the agent
 * (`/chat/interrupt`) and puts the message back in the field. The next send carries the id
 * of the message it corrects (`replaces`). « Modifier » does the same for the last message.
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

// A stream that stays open from `send` until `stop` (or `end`), like the real one.
const stream = vi.hoisted(() => ({
  callbacks: {} as Record<string, (...args: string[]) => void>,
  send: vi.fn(),
  stop: vi.fn(),
  end: () => {},
}))
vi.mock('../../hooks/useAgUiStream', async () => {
  const { useState } = await import('react')
  return {
    useAgUiStream: (callbacks: Record<string, (...args: string[]) => void>) => {
      stream.callbacks = callbacks
      const [isStreaming, setIsStreaming] = useState(false)
      stream.end = () => setIsStreaming(false)
      return {
        isStreaming,
        send: (...args: unknown[]) => {
          stream.send(...args)
          setIsStreaming(true)
          return new Promise<void>(() => {})
        },
        stop: () => {
          stream.stop()
          setIsStreaming(false)
        },
      }
    },
  }
})

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

const QUESTION = 'Crée un événement test à elle demain'
const CORRECTED = 'Crée un événement test à Ael demain'

const HISTORY = [
  { id: 'm-1', role: 'user', content: 'Ajoute du parmesan', createdAt: '2026-10-05T10:00:00Z' },
  { id: 'm-2', role: 'assistant', content: 'Parmesan ajouté.', createdAt: '2026-10-05T10:00:05Z' },
  { id: 'm-3', role: 'user', content: 'Où en est mon budget ?', createdAt: '2026-10-05T11:00:00Z' },
  { id: 'm-4', role: 'assistant', content: 'Il reste 120 €.', createdAt: '2026-10-05T11:00:05Z' },
]

interface Api {
  history: unknown[]
  /** What `/agent/messages?limit=5` says: the look-up of a message whose echo is missing. */
  recent: unknown[]
  interrupt: () => Promise<{ ok: boolean; status: number; json: () => Promise<unknown> }>
  interrupts: { url: string; body: Record<string, unknown> }[]
}

let api: Api

const interruptStored = () =>
  Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ id: 'a-int', role: 'assistant' }) })

function stubApi(overrides: Partial<Api> = {}) {
  api = { history: HISTORY, recent: [], interrupt: interruptStored, interrupts: [], ...overrides }
  const reply = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) })
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.includes('/chat/interrupt')) {
        api.interrupts.push({ url, body: JSON.parse(String(init?.body)) })
        return api.interrupt()
      }
      if (url.includes('/agent/contexts')) return reply([])
      if (url.includes('limit=5')) return reply(api.recent)
      if (url.includes('/agent/messages')) return reply(api.history)
      return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) })
    }),
  )
}

const noop = vi.fn()

function Host() {
  const [contexts, setContexts] = useState<ContextState[]>([])
  return (
    <ChatWidget
      open
      sidebarTab="chat"
      onTabChange={noop}
      onClose={noop}
      onUnread={noop}
      agentState="idle"
      onAgentStateChange={noop}
      contexts={contexts}
      onContextsChange={setContexts}
      toolCalls={[]}
      onToolCallsChange={noop}
    />
  )
}

function publish(payload: Record<string, unknown>) {
  const source = MockEventSource.instances.find(
    (es) => new URL(es.url, 'http://localhost').searchParams.get('match') === '/chat/user-1',
  )
  if (!source) throw new Error('not subscribed to /chat/user-1')
  act(() => {
    source.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent)
  })
}

const field = () => screen.getByPlaceholderText('Demande à Maggie...') as HTMLInputElement

/** Sends `text`, then Maggie starts answering: the first words of `answer` are on screen. */
async function sendAndStartAnswer(user: ReturnType<typeof userEvent.setup>, text: string, answer = 'Je regarde') {
  await screen.findByText('Il reste 120 €.')
  await user.type(field(), text)
  await user.click(screen.getByRole('button', { name: 'Envoyer' }))
  await screen.findByTestId('chat-stop')
  act(() => {
    stream.callbacks.onRunStarted()
    stream.callbacks.onTextStart('a-9')
    stream.callbacks.onTextDelta('a-9', answer)
  })
  await screen.findByText(answer)
}

const echoOfQuestion = (text = QUESTION) =>
  publish({ id: 'm-9', role: 'user', content: text, createdAt: new Date().toISOString() })

/** The row of a message, to reach the menu beside its bubble. */
const rowOf = (text: string) => screen.getByText(text).parentElement as HTMLElement

describe('ChatWidget — stop and correct', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    MockEventSource.instances = []
    stream.send.mockReset()
    stream.stop.mockReset()
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    stubApi()
  })

  afterEach(() => {
    localStorage.removeItem('user')
  })

  test('« Arrêter » replaces « Envoyer » while Maggie answers, and goes back once she is done', async () => {
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await screen.findByText('Il reste 120 €.')
    expect(screen.queryByTestId('chat-stop')).not.toBeInTheDocument()

    await user.type(field(), QUESTION)
    await user.click(screen.getByRole('button', { name: 'Envoyer' }))

    const stop = await screen.findByTestId('chat-stop')
    expect(stop).toHaveAccessibleName('Arrêter')
    expect(stop).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Envoyer' })).not.toBeInTheDocument()

    act(() => stream.end())
    await waitFor(() => expect(screen.queryByTestId('chat-stop')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Envoyer' })).toBeInTheDocument()
  })

  test('a normal send carries no `replaces`', async () => {
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)

    expect(stream.send).toHaveBeenCalledTimes(1)
    expect(stream.send).toHaveBeenCalledWith(QUESTION, expect.any(String), undefined)
  })

  test('Arrêter closes the stream, tells the agent what was shown, and puts the message back in the field', async () => {
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)
    echoOfQuestion()

    await user.click(screen.getByTestId('chat-stop'))

    expect(stream.stop).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(api.interrupts).toHaveLength(1))
    expect(api.interrupts[0].body).toEqual({ messageId: 'a-9', spokenText: 'Je regarde' })

    // The pair is gone from the thread, the words typed are back, cursor behind them.
    expect(screen.queryByText(QUESTION)).not.toBeInTheDocument()
    expect(screen.queryByText('Je regarde')).not.toBeInTheDocument()
    expect(field()).toHaveValue(QUESTION)
    await waitFor(() => expect(field()).toHaveFocus())
    expect(field().selectionStart).toBe(QUESTION.length)
    expect(field().selectionEnd).toBe(QUESTION.length)
    expect(screen.getByRole('button', { name: 'Envoyer' })).toBeInTheDocument()
  })

  test('the next send carries the id of the stopped message, and only that one', async () => {
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)
    echoOfQuestion()
    await user.click(screen.getByTestId('chat-stop'))
    await waitFor(() => expect(field()).toHaveValue(QUESTION))

    // Selected and overwritten: a field emptied on the way would give the correction up.
    await user.type(field(), `{Control>}a{/Control}${CORRECTED}`)
    await user.click(screen.getByRole('button', { name: 'Envoyer' }))

    await waitFor(() => expect(stream.send).toHaveBeenCalledTimes(2))
    expect(stream.send).toHaveBeenLastCalledWith(CORRECTED, expect.any(String), 'm-9')
    expect(screen.getByText(CORRECTED)).toBeInTheDocument()
    expect(screen.queryByText(QUESTION)).not.toBeInTheDocument()

    // A third message corrects nothing.
    act(() => stream.end())
    await user.type(field(), 'Merci')
    await user.click(screen.getByRole('button', { name: 'Envoyer' }))
    await waitFor(() => expect(stream.send).toHaveBeenCalledTimes(3))
    expect(stream.send).toHaveBeenLastCalledWith('Merci', expect.any(String), undefined)
  })

  test('the answer stored as interrupted, published before and after the response, stays out of the thread', async () => {
    let respond: () => void = () => {}
    stubApi({
      interrupt: () =>
        new Promise((resolve) => {
          respond = () => resolve({ ok: true, status: 200, json: () => Promise.resolve({ id: 'a-int' }) })
        }),
    })
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)
    echoOfQuestion()
    await user.click(screen.getByTestId('chat-stop'))
    await waitFor(() => expect(api.interrupts).toHaveLength(1))

    const interrupted = { id: 'a-int', role: 'assistant', content: 'Je regarde', interrupted: true }
    publish(interrupted)
    expect(screen.queryByText('Je regarde')).not.toBeInTheDocument()

    await act(async () => respond())
    publish(interrupted)
    expect(screen.queryByText('Je regarde')).not.toBeInTheDocument()
  })

  test('a message stopped before its echo is looked up on the server, and its late echo does not bring it back', async () => {
    stubApi({ recent: [{ id: 'm-9', role: 'user', content: QUESTION, createdAt: '2026-10-05T12:00:00Z' }] })
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)

    await user.click(screen.getByTestId('chat-stop'))
    await waitFor(() => expect(field()).toHaveValue(QUESTION))
    echoOfQuestion()
    expect(screen.queryByText(QUESTION)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Envoyer' }))
    await waitFor(() => expect(stream.send).toHaveBeenCalledTimes(2))
    expect(stream.send).toHaveBeenLastCalledWith(QUESTION, expect.any(String), 'm-9')
  })

  test('without a message it can identify, the next send replaces nothing', async () => {
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)

    await user.click(screen.getByTestId('chat-stop'))
    await waitFor(() => expect(field()).toHaveValue(QUESTION))
    await user.click(screen.getByRole('button', { name: 'Envoyer' }))

    await waitFor(() => expect(stream.send).toHaveBeenCalledTimes(2))
    expect(stream.send).toHaveBeenLastCalledWith(QUESTION, expect.any(String), undefined)
  })

  test('a refused interrupt says so quietly, and the message is back in the field all the same', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    stubApi({ interrupt: () => Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) }) })
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)
    echoOfQuestion()

    await user.click(screen.getByTestId('chat-stop'))

    expect(await screen.findByText("L'arrêt n'a pas pu être confirmé.")).toBeInTheDocument()
    expect(field()).toHaveValue(QUESTION)
    expect(screen.queryByText('Je regarde')).not.toBeInTheDocument()
    // And the correction still goes out with the id.
    await user.click(screen.getByRole('button', { name: 'Envoyer' }))
    await waitFor(() => expect(stream.send).toHaveBeenCalledTimes(2))
    expect(stream.send).toHaveBeenLastCalledWith(QUESTION, expect.any(String), 'm-9')
  })

  test('an interrupt that never reaches the agent leaves the same result', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    stubApi({ interrupt: () => Promise.reject(new Error('network')) })
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)
    echoOfQuestion()

    await user.click(screen.getByTestId('chat-stop'))

    expect(await screen.findByText("L'arrêt n'a pas pu être confirmé.")).toBeInTheDocument()
    expect(field()).toHaveValue(QUESTION)
  })

  test('emptying the field gives up the correction', async () => {
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)
    echoOfQuestion()
    await user.click(screen.getByTestId('chat-stop'))
    await waitFor(() => expect(field()).toHaveValue(QUESTION))

    await user.clear(field())
    await user.type(field(), 'Autre chose')
    await user.click(screen.getByRole('button', { name: 'Envoyer' }))

    await waitFor(() => expect(stream.send).toHaveBeenCalledTimes(2))
    expect(stream.send).toHaveBeenLastCalledWith('Autre chose', expect.any(String), undefined)
  })

  test('a 400 on a correction is retried as a plain message, under the same key', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const user = userEvent.setup()
    renderWithTheme(<Host />)
    await sendAndStartAnswer(user, QUESTION)
    echoOfQuestion()
    await user.click(screen.getByTestId('chat-stop'))
    await waitFor(() => expect(field()).toHaveValue(QUESTION))
    await user.click(screen.getByRole('button', { name: 'Envoyer' }))
    await waitFor(() => expect(stream.send).toHaveBeenCalledTimes(2))
    expect(stream.send).toHaveBeenLastCalledWith(QUESTION, expect.any(String), 'm-9')
    const key = stream.send.mock.calls[1][1]

    act(() => {
      stream.callbacks.onError('HTTP 400')
      stream.end()
    })
    await user.click(await screen.findByRole('button', { name: 'Réessayer' }))

    await waitFor(() => expect(stream.send).toHaveBeenCalledTimes(3))
    expect(stream.send).toHaveBeenLastCalledWith(QUESTION, key, undefined)
  })

  describe('« Modifier » the last message', () => {
    test('is offered on the last message of the owner only', async () => {
      const user = userEvent.setup()
      renderWithTheme(<Host />)
      await screen.findByText('Il reste 120 €.')

      await user.click(within(rowOf('Où en est mon budget ?')).getByRole('button', { name: 'Actions du message' }))
      expect(screen.getByTestId('chat-edit-last')).toHaveTextContent('Modifier')
      await user.keyboard('{Escape}')
      await waitFor(() => expect(screen.queryByTestId('chat-edit-last')).not.toBeInTheDocument())

      await user.click(within(rowOf('Ajoute du parmesan')).getByRole('button', { name: 'Actions du message' }))
      expect(screen.getByRole('menuitem', { name: 'Supprimer' })).toBeInTheDocument()
      expect(screen.queryByTestId('chat-edit-last')).not.toBeInTheDocument()
      await user.keyboard('{Escape}')

      // Nor on an answer of Maggie's.
      await user.click(within(rowOf('Il reste 120 €.')).getByRole('button', { name: 'Actions du message' }))
      expect(screen.queryByTestId('chat-edit-last')).not.toBeInTheDocument()
    })

    test('puts the message in the field, and the send replaces it and its answer', async () => {
      const user = userEvent.setup()
      renderWithTheme(<Host />)
      await screen.findByText('Il reste 120 €.')

      await user.click(within(rowOf('Où en est mon budget ?')).getByRole('button', { name: 'Actions du message' }))
      await user.click(screen.getByTestId('chat-edit-last'))

      expect(field()).toHaveValue('Où en est mon budget ?')
      await waitFor(() => expect(field().selectionStart).toBe('Où en est mon budget ?'.length))
      expect(stream.stop).not.toHaveBeenCalled()
      expect(api.interrupts).toHaveLength(0)

      await user.type(field(), '{Control>}a{/Control}Où en est mon budget courses ?')
      await user.click(screen.getByRole('button', { name: 'Envoyer' }))

      expect(stream.send).toHaveBeenCalledWith('Où en est mon budget courses ?', expect.any(String), 'm-3')
      expect(screen.queryByText('Où en est mon budget ?')).not.toBeInTheDocument()
      expect(screen.queryByText('Il reste 120 €.')).not.toBeInTheDocument()
      // The earlier exchange is left alone.
      expect(screen.getByText('Ajoute du parmesan')).toBeInTheDocument()
      expect(screen.getByText('Parmesan ajouté.')).toBeInTheDocument()
    })

    test('while Maggie still answers, stops her first', async () => {
      stubApi({ history: [{ id: 'm-1', role: 'user', content: 'Ajoute du parmesan', createdAt: '2026-10-05T10:00:00Z' }] })
      const user = userEvent.setup()
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')
      await user.type(field(), QUESTION)
      await user.click(screen.getByRole('button', { name: 'Envoyer' }))
      await screen.findByTestId('chat-stop')
      act(() => {
        stream.callbacks.onTextStart('a-9')
        stream.callbacks.onTextDelta('a-9', 'Je regarde')
      })
      echoOfQuestion()

      await user.click(within(rowOf(QUESTION)).getByRole('button', { name: 'Actions du message' }))
      await user.click(screen.getByTestId('chat-edit-last'))

      expect(stream.stop).toHaveBeenCalledTimes(1)
      await waitFor(() => expect(api.interrupts).toHaveLength(1))
      expect(api.interrupts[0].body).toEqual({ messageId: 'a-9', spokenText: 'Je regarde' })
      expect(field()).toHaveValue(QUESTION)
      expect(screen.queryByText('Je regarde')).not.toBeInTheDocument()
    })
  })
})
