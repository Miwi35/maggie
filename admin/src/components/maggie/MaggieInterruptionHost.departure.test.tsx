import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MaggieInterruptionHost } from './MaggieInterruptionHost'
import { veilleuseDarkTheme } from '../../theme'

vi.mock('./chime', () => ({ playChime: vi.fn() }))
vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }))

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

const WAZE = 'https://waze.com/ul?ll=48.10%2C-1.67&navigate=yes'
const fetchMock = vi.fn()
const open = vi.fn()

const departure = {
  '@id': '/api/notifications/n9',
  id: 'n9',
  type: 'departure',
  title: 'Pars maintenant pour Dentiste',
  body: null,
  relatedEntityIri: '/api/trips/01TRIP',
  readAt: null,
}

const respond = (trip: { ok: boolean; navigationUrl?: unknown }) =>
  fetchMock.mockImplementation((url: string) =>
    Promise.resolve(
      url.startsWith('/api/trips/')
        ? { ok: trip.ok, status: trip.ok ? 200 : 404, json: async () => ({ navigationUrl: trip.navigationUrl }) }
        : { ok: true, status: 200, json: async () => [] },
    ),
  )

function setup() {
  render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <MaggieInterruptionHost chatOpen={false} onOpenChat={vi.fn()} />
    </ThemeProvider>,
  )
}

describe('MaggieInterruptionHost — departure alert (MAG-659)', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
    localStorage.setItem('token', 'jwt')
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    open.mockReset()
    vi.stubGlobal('open', open)
    update.mockReset().mockResolvedValue({})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('shows the title with « Y aller », and the click opens the navigation link in a new tab', async () => {
    respond({ ok: true, navigationUrl: WAZE })
    setup()
    FakeEventSource.instances[0].emit(departure)

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Pars maintenant pour Dentiste')

    await userEvent.click(screen.getByRole('button', { name: 'Y aller' }))

    expect(open).toHaveBeenCalledWith(WAZE, '_blank', 'noopener,noreferrer')
    expect(update).toHaveBeenCalledWith(
      'notifications',
      expect.objectContaining({ id: '/api/notifications/n9', data: { readAt: expect.any(String) } }),
    )
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  })

  test('a trip that cannot be read opens nothing and says so, the alert stays', async () => {
    respond({ ok: false })
    setup()
    FakeEventSource.instances[0].emit(departure)

    await userEvent.click(await screen.findByRole('button', { name: 'Y aller' }))

    expect(open).not.toHaveBeenCalled()
    expect(await screen.findByText(/itinéraire est introuvable/)).toBeInTheDocument()
    expect(update).not.toHaveBeenCalled()
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })

  test('a link that is not a web link is never opened', async () => {
    respond({ ok: true, navigationUrl: 'javascript:alert(1)' })
    setup()
    FakeEventSource.instances[0].emit(departure)

    await userEvent.click(await screen.findByRole('button', { name: 'Y aller' }))

    expect(open).not.toHaveBeenCalled()
    expect(await screen.findByText(/itinéraire est introuvable/)).toBeInTheDocument()
  })

  test('« Plus tard » leaves the alert unread and opens nothing', async () => {
    respond({ ok: true, navigationUrl: WAZE })
    setup()
    FakeEventSource.instances[0].emit(departure)

    await userEvent.click(await screen.findByRole('button', { name: 'Plus tard' }))

    expect(open).not.toHaveBeenCalled()
    expect(update).not.toHaveBeenCalled()
  })
})
