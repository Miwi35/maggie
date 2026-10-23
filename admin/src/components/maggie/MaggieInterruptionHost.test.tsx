import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MaggieInterruptionHost } from './MaggieInterruptionHost'
import { veilleuseDarkTheme } from '../../theme'

vi.mock('./chime', () => ({ playChime: vi.fn() }))

const navigate = vi.fn()
vi.mock('react-router-dom', () => ({ useNavigate: () => navigate }))

const update = vi.fn()
vi.mock('react-admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-admin')>()),
  useDataProvider: () => ({ update }),
}))

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onmessage: ((event: { data: string }) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    FakeEventSource.instances.push(this)
  }
  emit(payload: unknown) {
    act(() => this.onmessage?.({ data: JSON.stringify(payload) }))
  }
}

const fetchMock = vi.fn()
const onOpenChat = vi.fn()

const reminder = {
  '@id': '/api/notifications/n1',
  id: 'n1',
  type: 'reminder',
  title: 'close 27',
  body: '15',
  relatedEntityIri: '/api/events/e1',
  readAt: null,
}

const approval = { id: 'a1', toolName: 'delete_event', summary: 'Supprimer « Psychomot »', status: 'pending' }

const feed = () => FakeEventSource.instances[0]

function setup() {
  render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <MaggieInterruptionHost chatOpen={false} onOpenChat={onOpenChat} />
    </ThemeProvider>,
  )
}

describe('MaggieInterruptionHost', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
    localStorage.setItem('token', 'jwt')
    fetchMock.mockReset().mockImplementation((url: string) =>
      Promise.resolve(
        url.startsWith('/agent/approvals?')
          ? { ok: true, status: 200, json: async () => [] }
          : { ok: true, status: 200, json: async () => ({}) },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    navigate.mockReset()
    update.mockReset().mockResolvedValue({})
    onOpenChat.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('a reminder shows its title and time, with « C\'est noté », its link and « Plus tard »', async () => {
    setup()
    feed().emit(reminder)

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('close 27')
    expect(dialog).toHaveTextContent('Dans 15 min')
    expect(screen.getByRole('button', { name: "C'est noté" })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: "Voir l'événement" })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Plus tard' })).toBeInTheDocument()
  })

  test('« Voir l\'événement » goes to the event and marks it read', async () => {
    setup()
    feed().emit(reminder)

    await userEvent.click(await screen.findByRole('button', { name: "Voir l'événement" }))

    expect(navigate).toHaveBeenCalledWith('/calendar?eventId=%2Fapi%2Fevents%2Fe1')
    expect(update).toHaveBeenCalledWith(
      'notifications',
      expect.objectContaining({ id: '/api/notifications/n1', data: { readAt: expect.any(String) } }),
    )
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(onOpenChat).not.toHaveBeenCalled()
  })

  test('« C\'est noté » marks it read and closes, without opening the event, and it does not come back', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      setup()
      feed().emit(reminder)

      await userEvent.click(await screen.findByRole('button', { name: "C'est noté" }))

      expect(update).toHaveBeenCalledTimes(1)
      expect(update).toHaveBeenCalledWith(
        'notifications',
        expect.objectContaining({ id: '/api/notifications/n1', data: { readAt: expect.any(String) } }),
      )
      expect(navigate).not.toHaveBeenCalled()
      expect(screen.queryByRole('alertdialog')).toBeNull()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(60 * 60 * 1000)
      })
      expect(screen.queryByRole('alertdialog')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  describe('an envelope past its threshold (MAG-49)', () => {
    const envelopeAlert = {
      ...reminder,
      '@id': '/api/notifications/n3',
      id: 'n3',
      type: 'finance',
      title: 'Loisirs : 83 % du budget (250 / 300 €)',
      body: null,
      relatedEntityIri: '/finance/budgets',
    }

    test('shows where the envelope stands, with « C\'est noté » and « Voir les enveloppes »', async () => {
      setup()
      feed().emit(envelopeAlert)

      const dialog = await screen.findByRole('alertdialog')
      expect(dialog).toHaveTextContent('Loisirs : 83 % du budget (250 / 300 €)')
      expect(screen.getByRole('button', { name: "C'est noté" })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Voir les enveloppes' })).toBeInTheDocument()
    })

    test('« C\'est noté » marks it read and stays put', async () => {
      setup()
      feed().emit(envelopeAlert)

      await userEvent.click(await screen.findByRole('button', { name: "C'est noté" }))

      expect(update).toHaveBeenCalledWith(
        'notifications',
        expect.objectContaining({ id: '/api/notifications/n3', data: { readAt: expect.any(String) } }),
      )
      expect(navigate).not.toHaveBeenCalled()
      expect(screen.queryByRole('alertdialog')).toBeNull()
    })

    test('« Voir les enveloppes » opens the envelopes and marks it read', async () => {
      setup()
      feed().emit(envelopeAlert)

      await userEvent.click(await screen.findByRole('button', { name: 'Voir les enveloppes' }))

      expect(navigate).toHaveBeenCalledWith('/envelopes')
      expect(update).toHaveBeenCalledWith(
        'notifications',
        expect.objectContaining({ id: '/api/notifications/n3', data: { readAt: expect.any(String) } }),
      )
      expect(screen.queryByRole('alertdialog')).toBeNull()
    })

    test('a read that fails still closes it: the bell keeps it unread', async () => {
      update.mockReset().mockRejectedValue(new Error('offline'))
      setup()
      feed().emit(envelopeAlert)

      await userEvent.click(await screen.findByRole('button', { name: "C'est noté" }))

      expect(update).toHaveBeenCalledTimes(1)
      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    })
  })

  test('a notification with nowhere to go is acknowledged', async () => {
    setup()
    feed().emit({ ...reminder, type: 'proaction', body: 'Détail', relatedEntityIri: null })

    await userEvent.click(await screen.findByRole('button', { name: 'Compris' }))

    expect(navigate).not.toHaveBeenCalled()
    expect(update).toHaveBeenCalled()
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  test('« Plus tard » leaves the notification unread and shows the next one', async () => {
    setup()
    feed().emit(reminder)
    feed().emit({ ...reminder, '@id': '/api/notifications/n2', title: 'Seconde' })

    await userEvent.click(await screen.findByRole('button', { name: 'Plus tard' }))

    expect(update).not.toHaveBeenCalled()
    expect(await screen.findByText('Seconde')).toBeInTheDocument()
  })

  test('a finished proaction still opens the chat', async () => {
    setup()
    feed().emit({ id: 'p1', status: 'completed', response: 'Ton colis est arrivé.' })

    await userEvent.click(await screen.findByRole('button', { name: 'Ouvrir le chat' }))

    expect(onOpenChat).toHaveBeenCalledTimes(1)
  })

  test('an action waiting for an answer offers Autoriser and Refuser; Autoriser approves it', async () => {
    setup()
    feed().emit(approval)

    expect(await screen.findByText('Supprimer « Psychomot »')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Autoriser' }))

    expect(fetchMock).toHaveBeenCalledWith(
      '/agent/approvals/a1/approve?channel=notification',
      expect.objectContaining({ method: 'POST', headers: { Authorization: 'Bearer jwt' } }),
    )
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  })

  test('Refuser denies it', async () => {
    setup()
    feed().emit(approval)

    await userEvent.click(await screen.findByRole('button', { name: 'Refuser' }))

    expect(fetchMock).toHaveBeenCalledWith('/agent/approvals/a1/deny?channel=notification', expect.objectContaining({ method: 'POST' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  })

  test('an answer that did not go keeps the question and says so', async () => {
    setup()
    feed().emit(approval)
    fetchMock.mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({}) })

    await userEvent.click(await screen.findByRole('button', { name: 'Autoriser' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Ta réponse n'est pas partie")
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Autoriser' })).toBeEnabled()
  })

  test.each([404, 409, 410])('a question the agent no longer holds (%i) is closed', async (status) => {
    setup()
    feed().emit(approval)
    fetchMock.mockResolvedValueOnce({ ok: false, status, json: async () => ({}) })

    await userEvent.click(await screen.findByRole('button', { name: 'Refuser' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  })

  test('« Plus tard » waits while the answer is on its way', async () => {
    setup()
    feed().emit(approval)
    fetchMock.mockReturnValueOnce(new Promise(() => {}))

    await userEvent.click(await screen.findByRole('button', { name: 'Autoriser' }))

    expect(screen.getByRole('button', { name: 'Plus tard' })).toBeDisabled()
  })

  test('a failure of an answer already overtaken does not blame the next question', async () => {
    setup()
    feed().emit(approval)
    feed().emit({ ...approval, id: 'a2', summary: 'Supprimer « Dentiste »' })
    let fail: (reason: Error) => void = () => {}
    fetchMock.mockReturnValueOnce(new Promise((_resolve, reject) => (fail = reject)))

    await userEvent.click(await screen.findByRole('button', { name: 'Autoriser' }))
    feed().emit({ ...approval, status: 'approved' })
    expect(await screen.findByText('Supprimer « Dentiste »')).toBeInTheDocument()
    await act(async () => fail(new Error('network')))

    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('button', { name: 'Autoriser' })).toBeEnabled()
  })

  describe('a missing product (MAG-570)', () => {
    const shortage = {
      '@id': '/api/notifications/n9',
      id: 'n9',
      type: 'grocery',
      title: 'Il manque : parmesan',
      body: 'Pour Pâtes carbonara, ce soir.',
      relatedEntityIri: '/api/shortage-alerts/01K0SHORTAGE0000000000000A',
      actions: ['add'],
      readAt: null,
    }
    const url = '/api/shortage-alerts/01K0SHORTAGE0000000000000A/action'

    test('says what is missing, with « Ajouter à la liste » and « Plus tard »', async () => {
      setup()
      feed().emit(shortage)

      const dialog = await screen.findByRole('alertdialog')
      expect(dialog).toHaveTextContent('Il manque : parmesan')
      expect(dialog).toHaveTextContent('Pour Pâtes carbonara, ce soir.')
      expect(screen.getByRole('button', { name: 'Ajouter à la liste' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Plus tard' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Compris' })).toBeNull()
    })

    test('« Ajouter à la liste » posts the answer, closes, and leaves the read to the server', async () => {
      setup()
      feed().emit(shortage)

      await userEvent.click(await screen.findByRole('button', { name: 'Ajouter à la liste' }))

      expect(fetchMock).toHaveBeenCalledWith(
        url,
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ Authorization: 'Bearer jwt' }),
          body: JSON.stringify({ action: 'add' }),
        }),
      )
      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
      expect(update).not.toHaveBeenCalled()
    })

    test.each([404, 409])('an alert already settled (%i) is closed', async (status) => {
      setup()
      feed().emit(shortage)
      fetchMock.mockResolvedValueOnce({ ok: false, status, json: async () => ({}) })

      await userEvent.click(await screen.findByRole('button', { name: 'Ajouter à la liste' }))

      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    })

    test('an answer that did not go keeps the question and says so', async () => {
      setup()
      feed().emit(shortage)
      fetchMock.mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({}) })

      await userEvent.click(await screen.findByRole('button', { name: 'Ajouter à la liste' }))

      expect(await screen.findByRole('alert')).toHaveTextContent("Ta réponse n'est pas partie")
      expect(screen.getByRole('alertdialog')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Ajouter à la liste' })).toBeEnabled()
    })

    test('« Plus tard » posts nothing and keeps it unread', async () => {
      setup()
      feed().emit(shortage)

      await userEvent.click(await screen.findByRole('button', { name: 'Plus tard' }))

      expect(fetchMock.mock.calls.filter(([u]) => u === url)).toHaveLength(0)
      expect(update).not.toHaveBeenCalled()
      expect(screen.queryByRole('alertdialog')).toBeNull()
    })

    test('is withdrawn when the notification is deleted elsewhere', async () => {
      setup()
      feed().emit(shortage)
      await screen.findByRole('alertdialog')

      feed().emit({ '@id': shortage['@id'], deleted: true })

      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    })
  })
})
