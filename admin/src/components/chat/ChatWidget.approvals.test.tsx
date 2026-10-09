import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
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
vi.mock('../../hooks/useAgUiStream', () => ({
  useAgUiStream: () => ({ send: vi.fn(), isStreaming: false }),
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

function approvalsStream(): MockEventSource {
  const es = MockEventSource.instances.find(
    (i) => new URL(i.url, 'http://localhost').searchParams.getAll('match').includes('/approvals/user-1'),
  )
  if (!es) throw new Error('No subscription to the approvals topic')
  return es
}

const IN_ONE_HOUR = new Date(Date.now() + 60 * 60 * 1000).toISOString()

const deleteEvent = {
  id: 'a1',
  toolName: 'delete_event',
  arguments: { title: 'Dentiste' },
  status: 'pending',
  result: null,
  createdAt: '2026-10-06T08:00:00.000Z',
  expiresAt: IN_ONE_HOUR,
}

const props = {
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

function renderWidget() {
  return render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <ChatWidget {...props} />
    </ThemeProvider>,
  )
}

function stubFetch(pending: unknown[], onPost?: (url: string) => unknown) {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(onPost?.(url)) })
    }
    const body = url.includes('/agent/approvals') ? pending : []
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('ChatWidget approvals (MAG-6)', () => {
  beforeEach(() => {
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('shows a card for each pending action in the thread', async () => {
    stubFetch([deleteEvent])
    renderWidget()

    const card = await screen.findByTestId('approval-card')
    expect(within(card).getByText('delete_event')).toBeInTheDocument()
    expect(within(card).getByText('Dentiste')).toBeInTheDocument()
  })

  test('Autoriser sends the approval and the card ends validée', async () => {
    const user = userEvent.setup()
    const fetchMock = stubFetch([deleteEvent], () => ({ ...deleteEvent, status: 'approved', result: '{"ok":true}' }))
    renderWidget()

    await user.click(await screen.findByRole('button', { name: 'Autoriser' }))

    await waitFor(() => expect(screen.getByTestId('approval-card')).toHaveAttribute('data-status', 'approved'))
    expect(fetchMock).toHaveBeenCalledWith('/agent/approvals/a1/approve', expect.objectContaining({ method: 'POST' }))
  })

  test('Refuser sends the refusal and the card ends refusée', async () => {
    const user = userEvent.setup()
    const fetchMock = stubFetch([deleteEvent], () => ({ ...deleteEvent, status: 'denied' }))
    renderWidget()

    await user.click(await screen.findByRole('button', { name: 'Refuser' }))

    await waitFor(() => expect(screen.getByTestId('approval-card')).toHaveAttribute('data-status', 'denied'))
    expect(fetchMock).toHaveBeenCalledWith('/agent/approvals/a1/deny', expect.objectContaining({ method: 'POST' }))
  })

  test('a card appears when Mercure announces a new pending action', async () => {
    stubFetch([])
    renderWidget()
    expect(screen.queryByTestId('approval-card')).not.toBeInTheDocument()

    act(() => approvalsStream().onmessage?.({ data: JSON.stringify(deleteEvent) } as MessageEvent))

    expect(await screen.findByTestId('approval-card')).toHaveAttribute('data-status', 'pending')
  })

  test('the approvals ride the contexts connection: no extra EventSource', async () => {
    stubFetch([])
    renderWidget()

    const matches = new URL(approvalsStream().url, 'http://localhost').searchParams.getAll('match')
    expect(matches).toContain('/contexts/user-1')
    expect(MockEventSource.instances.filter((i) => i.url.includes('approvals'))).toHaveLength(1)
  })

  test('a Mercure update made from another device settles the card', async () => {
    stubFetch([deleteEvent])
    renderWidget()
    await screen.findByTestId('approval-card')

    act(() =>
      approvalsStream().onmessage?.({
        data: JSON.stringify({ ...deleteEvent, status: 'denied' }),
      } as MessageEvent),
    )

    await waitFor(() => expect(screen.getByTestId('approval-card')).toHaveAttribute('data-status', 'denied'))
    expect(screen.queryByRole('button', { name: 'Autoriser' })).not.toBeInTheDocument()
  })

  test('a failed answer shows an error on the card', async () => {
    const user = userEvent.setup()
    stubFetch([deleteEvent])
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) =>
        init?.method === 'POST'
          ? Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) })
          : Promise.resolve({
              ok: true,
              status: 200,
              json: () => Promise.resolve(url.includes('/agent/approvals') ? [deleteEvent] : []),
            }),
      ),
    )
    renderWidget()

    await user.click(await screen.findByRole('button', { name: 'Autoriser' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Impossible d'envoyer ta réponse")
    expect(screen.getByRole('button', { name: 'Autoriser' })).toBeEnabled()
  })

  test('the Mind tab carries the number of actions waiting', async () => {
    stubFetch([deleteEvent, { ...deleteEvent, id: 'a2' }])
    renderWidget()

    const badge = await screen.findByTestId('mind-approvals-badge')
    expect(badge).toHaveTextContent('2')
  })

  test('the badge counts only the actions still waiting', async () => {
    stubFetch([deleteEvent, { ...deleteEvent, id: 'a2' }])
    renderWidget()
    await screen.findByTestId('mind-approvals-badge')

    act(() =>
      approvalsStream().onmessage?.({
        data: JSON.stringify({ ...deleteEvent, status: 'approved', result: '{}' }),
      } as MessageEvent),
    )

    await waitFor(() => expect(screen.getByTestId('mind-approvals-badge')).toHaveTextContent('1'))
  })

  test('no badge when nothing waits', async () => {
    const fetchMock = stubFetch([])
    renderWidget()

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/agent/approvals?status=pending', expect.anything()))
    expect(screen.queryByTestId('mind-approvals-badge')).not.toBeInTheDocument()
  })
})
