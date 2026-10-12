import { useState } from 'react'
import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithTheme } from '../../test/renderWithTheme'
import { ChatWidget } from './ChatWidget'
import type { ContextState } from '../mind/types'

/**
 * Consulting and cleaning the threads from the chat (MAG-342).
 *
 * The conversation is one; its threads are internal filing. They are reached from
 * an icon next to the chat search, and a thread or a message is deleted with an
 * « Annuler » that holds the request back: nothing is sent before the delay is over.
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
const stream = vi.hoisted(() => ({ callbacks: {} as Record<string, (...args: unknown[]) => void> }))
vi.mock('../../hooks/useAgUiStream', () => ({
  useAgUiStream: (callbacks: Record<string, (...args: unknown[]) => void>) => {
    stream.callbacks = callbacks
    return { send: vi.fn(), isStreaming: false }
  },
}))

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

const THREADS = [
  { id: 'ctx-1', label: 'Courses de la semaine', status: 'active', summary: null, messageCount: 2 },
  { id: 'ctx-2', label: 'Budget', status: 'dormant', summary: null, messageCount: 1 },
  { id: 'ctx-3', label: 'Vieux sujet', status: 'closed', summary: null, messageCount: 0 },
]

const MESSAGES = [
  { id: 'm-1', role: 'user', content: 'Ajoute du parmesan', contextId: 'ctx-1', createdAt: '2026-10-05T10:00:00Z' },
  { id: 'm-2', role: 'assistant', content: 'Parmesan ajouté.', contextId: 'ctx-1', createdAt: '2026-10-05T10:00:05Z' },
  { id: 'm-3', role: 'user', content: 'Où en est mon budget ?', contextId: 'ctx-2', createdAt: '2026-10-05T11:00:00Z' },
]

interface Call {
  method: string
  url: string
}

let calls: Call[]

/** The agent API: GET lists the threads and the history, DELETE answers with `deleteStatus`. */
function stubApi({ deleteStatus = 200 }: { deleteStatus?: number } = {}) {
  calls = []
  const reply = (status: number, body: unknown) =>
    Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) })

  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      calls.push({ method, url })
      if (method === 'DELETE') return reply(deleteStatus, {})
      if (url.includes('/agent/contexts')) return reply(200, THREADS)
      if (url.includes('/agent/messages')) return reply(200, MESSAGES)
      return reply(404, {})
    }),
  )
}

const deletes = () => calls.filter((c) => c.method === 'DELETE').map((c) => c.url)

const noop = vi.fn()

/** Holds the threads the way `Layout` does, above the widget. */
function Host({ sidebarTab = 'chat' }: { sidebarTab?: 'chat' | 'mind' }) {
  const [contexts, setContexts] = useState<ContextState[]>([])
  return (
    <ChatWidget
      open
      sidebarTab={sidebarTab}
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

function source(topic: string): MockEventSource {
  const found = MockEventSource.instances.find(
    (es) => new URL(es.url, 'http://localhost').searchParams.get('match') === topic,
  )
  if (!found) throw new Error(`not subscribed to ${topic}`)
  return found
}

function publish(topic: string, payload: Record<string, unknown>) {
  act(() => {
    source(topic).onmessage?.({ data: JSON.stringify(payload) } as MessageEvent)
  })
}

/** Lets the delay run out. Real time keeps flowing (`shouldAdvanceTime`), so `waitFor` still works. */
async function waitOutUndoDelay() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(6100)
  })
}

async function openThreads(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Fils de discussion' }))
  return screen.findByRole('dialog', { name: 'Fils de discussion' })
}

/** Deletes a thread from the open list and confirms. */
async function deleteThread(user: ReturnType<typeof userEvent.setup>, label: string) {
  await user.click(screen.getByRole('button', { name: `Supprimer le fil « ${label} »` }))
  const confirmation = await screen.findByRole('dialog', { name: `Supprimer le fil « ${label} » ?` })
  await user.click(within(confirmation).getByRole('button', { name: 'Supprimer' }))
  await waitFor(() => expect(screen.queryByRole('dialog', { name: /Supprimer le fil/ })).not.toBeInTheDocument())
}

const snackbar = () => screen.getByRole('alert')

describe('threads in the chat', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.stubGlobal('EventSource', MockEventSource)
    MockEventSource.instances = []
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
    localStorage.removeItem('chat_lastReadMessageId')
    stubApi()
  })

  afterEach(() => {
    vi.useRealTimers()
    localStorage.removeItem('user')
    vi.restoreAllMocks()
  })

  describe('the access', () => {
    test('is one icon right next to the search button, in the chat tab', () => {
      renderWithTheme(<Host />)

      const threads = screen.getByRole('button', { name: 'Fils de discussion' })
      const search = screen.getByRole('button', { name: 'Rechercher dans la conversation' })
      expect(threads.parentElement).toBe(search.parentElement)
      expect(threads.nextElementSibling).toBe(search)
    })

    test('is gone while searching, and from the Mind tab', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      const { unmount } = renderWithTheme(<Host />)

      await user.click(screen.getByRole('button', { name: 'Rechercher dans la conversation' }))
      expect(screen.queryByRole('button', { name: 'Fils de discussion' })).not.toBeInTheDocument()

      unmount()
      renderWithTheme(<Host sidebarTab="mind" />)
      expect(screen.queryByRole('button', { name: 'Fils de discussion' })).not.toBeInTheDocument()
    })

    test('the Mind tab no longer lists the threads', async () => {
      renderWithTheme(<Host sidebarTab="mind" />)

      await waitFor(() => expect(calls.some((c) => c.url.includes('/agent/contexts'))).toBe(true))
      expect(screen.queryByTestId('mind-contexts')).not.toBeInTheDocument()
      expect(screen.getByTestId('mind-activity')).toBeInTheDocument()
    })

    test('opens the list of threads, closed ones included, with the handles the journeys count', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)

      const dialog = await openThreads(user)

      expect(calls.some((c) => c.url.includes('/agent/contexts?includeClosed=true'))).toBe(true)
      const list = within(dialog).getByTestId('mind-contexts')
      const rows = within(list).getAllByTestId('mind-context')
      expect(rows.map((row) => row.getAttribute('data-status'))).toEqual(['active', 'dormant', 'closed'])
      expect(within(dialog).getByText('Courses de la semaine')).toBeInTheDocument()
    })

    test('asks the API again each time it is opened, for fresh counts', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await waitFor(() => expect(calls.filter((c) => c.url.includes('/agent/contexts')).length).toBe(1))

      await openThreads(user)

      await waitFor(() => expect(calls.filter((c) => c.url.includes('/agent/contexts')).length).toBe(2))
    })
  })

  describe('deleting a thread', () => {
    test.each([
      ['Courses de la semaine', 'Ce fil et ses 2 messages seront supprimés.'],
      ['Budget', 'Ce fil et 1 message seront supprimés.'],
      ['Vieux sujet', 'Ce fil sera supprimé.'],
    ])('says what goes with « %s » before deleting', async (label, sentence) => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openThreads(user)
      await screen.findByText(label)

      await user.click(screen.getByRole('button', { name: `Supprimer le fil « ${label} »` }))

      const confirmation = await screen.findByRole('dialog', { name: `Supprimer le fil « ${label} » ?` })
      expect(within(confirmation).getByText(sentence)).toBeInTheDocument()
      expect(deletes()).toEqual([])
    })

    test('cancelling the confirmation deletes nothing', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openThreads(user)
      await screen.findByText('Budget')

      await user.click(screen.getByRole('button', { name: 'Supprimer le fil « Budget »' }))
      const confirmation = await screen.findByRole('dialog', { name: /Supprimer le fil/ })
      await user.click(within(confirmation).getByRole('button', { name: 'Annuler' }))

      await waitFor(() => expect(screen.queryByRole('dialog', { name: /Supprimer le fil/ })).not.toBeInTheDocument())
      expect(screen.getByText('Budget')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      await waitOutUndoDelay()
      expect(deletes()).toEqual([])
    })

    test('once confirmed, the thread leaves the list and its messages leave the chat, at once', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')
      await openThreads(user)
      await screen.findByText('Courses de la semaine')

      await deleteThread(user, 'Courses de la semaine')

      const list = screen.getByTestId('mind-contexts')
      expect(within(list).queryByText('Courses de la semaine')).not.toBeInTheDocument()
      expect(within(list).getAllByTestId('mind-context')).toHaveLength(2)
      expect(screen.queryByText('Ajoute du parmesan')).not.toBeInTheDocument()
      expect(screen.queryByText('Parmesan ajouté.')).not.toBeInTheDocument()
      // The other thread's message stays.
      expect(screen.getByText('Où en est mon budget ?')).toBeInTheDocument()
      expect(within(snackbar()).getByText('Fil supprimé')).toBeInTheDocument()
      expect(deletes()).toEqual([])
    })

    test('« Annuler » brings the thread and its messages back, and the DELETE is never sent', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')
      await openThreads(user)
      await screen.findByText('Courses de la semaine')
      await deleteThread(user, 'Courses de la semaine')

      await user.click(within(snackbar()).getByRole('button', { name: 'Annuler' }))

      const rows = within(screen.getByTestId('mind-contexts')).getAllByTestId('mind-context')
      // Back where it was, first.
      expect(within(rows[0]).getByText('Courses de la semaine')).toBeInTheDocument()
      expect(rows).toHaveLength(3)
      expect(screen.getByText('Ajoute du parmesan')).toBeInTheDocument()
      expect(screen.getByText('Parmesan ajouté.')).toBeInTheDocument()
      await waitOutUndoDelay()
      expect(deletes()).toEqual([])
    })

    test('sends the DELETE when the delay is over, and only then', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')
      await openThreads(user)
      await screen.findByText('Courses de la semaine')
      await deleteThread(user, 'Courses de la semaine')

      await act(async () => {
        await vi.advanceTimersByTimeAsync(5000)
      })
      expect(deletes()).toEqual([])

      await waitOutUndoDelay()

      expect(deletes()).toEqual(['/agent/contexts/ctx-1'])
      await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
      expect(screen.queryByText('Courses de la semaine')).not.toBeInTheDocument()
    })

    test('a refused DELETE brings everything back, with a notification in French', async () => {
      stubApi({ deleteStatus: 500 })
      const logged = vi.spyOn(console, 'error').mockImplementation(() => {})
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')
      await openThreads(user)
      await screen.findByText('Courses de la semaine')
      await deleteThread(user, 'Courses de la semaine')

      await waitOutUndoDelay()

      expect(await screen.findByText('La suppression a échoué. Réessayez.')).toBeInTheDocument()
      expect(within(screen.getByTestId('mind-contexts')).getByText('Courses de la semaine')).toBeInTheDocument()
      expect(screen.getByText('Ajoute du parmesan')).toBeInTheDocument()
      // The technical message goes to the log, never to the screen.
      expect(screen.queryByText(/HTTP 500/)).not.toBeInTheDocument()
      expect(logged).toHaveBeenCalledWith(expect.stringContaining('Failed to delete'), expect.anything(), expect.any(Error))
    })

    test('a network failure is restored the same way', async () => {
      vi.spyOn(console, 'error').mockImplementation(() => {})
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')
      await openThreads(user)
      await screen.findByText('Courses de la semaine')
      await deleteThread(user, 'Courses de la semaine')
      const realFetch = fetch as unknown as (...args: unknown[]) => unknown
      vi.stubGlobal(
        'fetch',
        vi.fn((url: string, init?: RequestInit) =>
          init?.method === 'DELETE' ? Promise.reject(new TypeError('Failed to fetch')) : realFetch(url, init),
        ),
      )

      await waitOutUndoDelay()

      expect(await screen.findByText('La suppression a échoué. Réessayez.')).toBeInTheDocument()
      expect(screen.getByText('Ajoute du parmesan')).toBeInTheDocument()
      expect(screen.queryByText(/Failed to fetch/)).not.toBeInTheDocument()
    })

    test('a second deletion sends the first one at once', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openThreads(user)
      await screen.findByText('Courses de la semaine')
      await deleteThread(user, 'Courses de la semaine')

      await deleteThread(user, 'Budget')

      expect(deletes()).toEqual(['/agent/contexts/ctx-1'])
      await waitOutUndoDelay()
      expect(deletes()).toEqual(['/agent/contexts/ctx-1', '/agent/contexts/ctx-2'])
    })

    test('a summary arriving meanwhile does not bring the waiting thread back', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openThreads(user)
      await screen.findByText('Courses de la semaine')
      await deleteThread(user, 'Courses de la semaine')

      publish('/contexts/user-1', { id: 'ctx-1', label: 'Courses de la semaine', status: 'active', summary: 'Du parmesan.' })

      expect(within(screen.getByTestId('mind-contexts')).queryByText('Courses de la semaine')).not.toBeInTheDocument()
    })

    test('an update keeps the message count the list was fetched with', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openThreads(user)
      await screen.findByText('Courses de la semaine')

      publish('/contexts/user-1', { id: 'ctx-1', label: 'Courses', status: 'active', summary: 'Du parmesan.' })
      await screen.findByText('Du parmesan.')
      await user.click(screen.getByRole('button', { name: 'Supprimer le fil « Courses »' }))

      expect(await screen.findByText('Ce fil et ses 2 messages seront supprimés.')).toBeInTheDocument()
    })
  })

  describe('deleting a message', () => {
    async function openMenuOf(user: ReturnType<typeof userEvent.setup>, text: string) {
      await screen.findByText(text)
      // The button sits beside the bubble, in the same row.
      const row = screen.getByText(text).parentElement as HTMLElement
      await user.click(within(row).getByRole('button', { name: 'Actions du message' }))
    }

    test('takes the message out of the chat from its menu, with no confirmation, then offers « Annuler »', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)

      await openMenuOf(user, 'Parmesan ajouté.')
      await user.click(screen.getByRole('menuitem', { name: 'Supprimer' }))

      expect(screen.queryByText('Parmesan ajouté.')).not.toBeInTheDocument()
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      // The rest of the thread is untouched.
      expect(screen.getByText('Ajoute du parmesan')).toBeInTheDocument()
      expect(within(snackbar()).getByText('Message supprimé')).toBeInTheDocument()
      expect(deletes()).toEqual([])
    })

    test('« Annuler » puts the message back in its place and sends nothing', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openMenuOf(user, 'Ajoute du parmesan')
      await user.click(screen.getByRole('menuitem', { name: 'Supprimer' }))

      await user.click(within(snackbar()).getByRole('button', { name: 'Annuler' }))

      const bubbles = ['Ajoute du parmesan', 'Parmesan ajouté.', 'Où en est mon budget ?']
      const shown = bubbles.map((text) => screen.getByText(text))
      // Same order as the history.
      expect(shown[0].compareDocumentPosition(shown[1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(shown[1].compareDocumentPosition(shown[2]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      await waitOutUndoDelay()
      expect(deletes()).toEqual([])
    })

    test('sends the DELETE of that one message when the delay is over', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openMenuOf(user, 'Parmesan ajouté.')
      await user.click(screen.getByRole('menuitem', { name: 'Supprimer' }))

      await waitOutUndoDelay()

      expect(deletes()).toEqual(['/agent/messages/m-2'])
    })

    test('a refused DELETE puts the message back, with a notification in French', async () => {
      stubApi({ deleteStatus: 500 })
      vi.spyOn(console, 'error').mockImplementation(() => {})
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openMenuOf(user, 'Parmesan ajouté.')
      await user.click(screen.getByRole('menuitem', { name: 'Supprimer' }))

      await waitOutUndoDelay()

      expect(await screen.findByText('La suppression a échoué. Réessayez.')).toBeInTheDocument()
      expect(screen.getByText('Parmesan ajouté.')).toBeInTheDocument()
    })

    test('a message not stored yet has no menu', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')

      await user.type(screen.getByPlaceholderText('Demande à Maggie...'), 'Bonjour')
      await user.click(screen.getByRole('button', { name: 'Envoyer' }))

      await screen.findByText('Bonjour')
      // The three stored messages have one; the one waiting for its id does not.
      expect(screen.getAllByRole('button', { name: 'Actions du message' })).toHaveLength(3)
    })
  })

  describe('deletions made elsewhere', () => {
    test('a thread deleted on the phone leaves an open list, without a reload', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openThreads(user)
      await screen.findByText('Budget')

      publish('/contexts/user-1', { id: 'ctx-2', deleted: true })

      await waitFor(() => expect(screen.queryByText('Budget')).not.toBeInTheDocument())
      expect(screen.getByText('Courses de la semaine')).toBeInTheDocument()
      expect(screen.getAllByTestId('mind-context')).toHaveLength(2)
    })

    test('a deletion event is never taken for a new thread', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await openThreads(user)
      await screen.findByText('Budget')

      publish('/contexts/user-1', { id: 'ctx-unknown', deleted: true })

      expect(screen.getAllByTestId('mind-context')).toHaveLength(3)
    })

    test('the messages of a thread deleted elsewhere leave the chat', async () => {
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')

      publish('/chat/user-1', { deleted: true, contextId: 'ctx-1', messageIds: ['m-1', 'm-2'] })

      await waitFor(() => expect(screen.queryByText('Ajoute du parmesan')).not.toBeInTheDocument())
      expect(screen.queryByText('Parmesan ajouté.')).not.toBeInTheDocument()
      expect(screen.getByText('Où en est mon budget ?')).toBeInTheDocument()
      // Not an empty bubble either: the payload carries no content.
      expect(screen.getAllByRole('button', { name: 'Actions du message' })).toHaveLength(1)
    })

    test('a message deleted elsewhere leaves the chat, and a replay changes nothing', async () => {
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')

      publish('/chat/user-1', { deleted: true, contextId: null, messageIds: ['m-2'] })
      publish('/chat/user-1', { deleted: true, contextId: null, messageIds: ['m-2'] })

      await waitFor(() => expect(screen.queryByText('Parmesan ajouté.')).not.toBeInTheDocument())
      expect(screen.getByText('Ajoute du parmesan')).toBeInTheDocument()
      expect(screen.getByText('Où en est mon budget ?')).toBeInTheDocument()
    })

    test('the echo of our own deletion finds nothing left to remove', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')
      await openThreads(user)
      await screen.findByText('Courses de la semaine')
      await deleteThread(user, 'Courses de la semaine')
      await waitOutUndoDelay()

      publish('/contexts/user-1', { id: 'ctx-1', deleted: true })
      publish('/chat/user-1', { deleted: true, contextId: 'ctx-1', messageIds: ['m-1', 'm-2'] })

      expect(screen.getAllByTestId('mind-context')).toHaveLength(2)
      expect(screen.getByText('Où en est mon budget ?')).toBeInTheDocument()
    })

    test('what was just said in this tab is filed in the thread the run was routed to', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')

      await user.type(screen.getByPlaceholderText('Demande à Maggie...'), 'Et du basilic ?')
      await user.click(screen.getByRole('button', { name: 'Envoyer' }))
      act(() => {
        stream.callbacks.onContextUpdate({ id: 'ctx-1', label: 'Courses de la semaine', status: 'active' })
        stream.callbacks.onTextStart('m-10')
        stream.callbacks.onTextDelta('m-10', 'Oui, du basilic.')
        stream.callbacks.onTextEnd('m-10')
      })
      await screen.findByText('Oui, du basilic.')
      await openThreads(user)
      await screen.findByText('Budget')

      await deleteThread(user, 'Courses de la semaine')

      // Neither the question nor the answer streamed here waits for a reload to go.
      expect(screen.queryByText('Et du basilic ?')).not.toBeInTheDocument()
      expect(screen.queryByText('Oui, du basilic.')).not.toBeInTheDocument()
      expect(screen.getByText('Où en est mon budget ?')).toBeInTheDocument()
    })

    test('a message learns its thread from the echo, so deleting the thread takes it out too', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderWithTheme(<Host />)
      await screen.findByText('Ajoute du parmesan')
      // Streamed from this tab: no thread on it until the Mercure echo says which.
      publish('/chat/user-1', { id: 'm-9', role: 'assistant', content: 'Et du basilic.', createdAt: '2026-10-05T10:01:00Z' })
      await screen.findByText('Et du basilic.')
      publish('/chat/user-1', {
        id: 'm-9',
        role: 'assistant',
        content: 'Et du basilic.',
        contextId: 'ctx-1',
        createdAt: '2026-10-05T10:01:00Z',
      })
      await openThreads(user)
      await screen.findByText('Courses de la semaine')

      await deleteThread(user, 'Courses de la semaine')

      expect(screen.queryByText('Et du basilic.')).not.toBeInTheDocument()
    })
  })
})
