import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../../theme'
import { emitMercure } from '../../../test/mercure'
import { AgendaCard } from './AgendaCard'
import type { AgendaHomeEvent, AgendaHomeState } from './agendaTimeline'
import type { HomeDay } from '../homeDay'

vi.mock('../../../hooks/useMercure', async () => (await import('../../../test/mercure')).mercureModule)

const NOW = '2026-10-14T14:12:00+02:00'

const event = (id: string, summary: string, startAt: string, endAt: string, extra: Partial<AgendaHomeEvent> = {}): AgendaHomeEvent => ({
  id,
  summary,
  description: null,
  location: null,
  locationShort: null,
  allDay: false,
  startAt,
  endAt,
  timeZone: 'Europe/Paris',
  status: 'confirmed',
  reminders: null,
  activity: null,
  agendaId: 'agenda-1',
  agendaName: 'Perso',
  agendaColor: '#4285F4',
  ...extra,
})

const stay: AgendaHomeEvent = {
  ...event('stay', 'Séjour', '', ''),
  allDay: true,
  startAt: null,
  endAt: null,
  startDate: '2026-10-13',
  endDate: '2026-10-16',
  lastDay: '2026-10-15',
}
const past = event('past', 'Réunion équipe', '2026-10-14T09:00:00+02:00', '2026-10-14T10:00:00+02:00', { locationShort: 'Salle 2' })
const running = event('running', 'Atelier', '2026-10-14T13:30:00+02:00', '2026-10-14T14:30:00+02:00')
const upcoming = event('upcoming', 'Dentiste', '2026-10-14T15:00:00+02:00', '2026-10-14T16:00:00+02:00', {
  description: 'Contrôle annuel',
  location: 'Cabinet Martin, 15 rue de la Paix',
  locationShort: 'Cabinet Martin',
  reminders: { useDefault: false, overrides: [{ method: 'popup', minutes: 30 }] },
})
const evening = event('evening', 'Yoga', '2026-10-14T18:00:00+02:00', '2026-10-14T19:00:00+02:00')

const today = (events: AgendaHomeEvent[], now: string | null = NOW): AgendaHomeState => ({ date: '2026-10-14', now, events })

const answer = (body: AgendaHomeState) => ({ ok: true, json: () => Promise.resolve(body) })

const Where = () => {
  const { pathname, search, state } = useLocation()

  return <output data-testid="where">{`${pathname}${search}|${JSON.stringify(state)}`}</output>
}

const renderCard = (day: HomeDay = 'today') =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={veilleuseLightTheme}>
        <AgendaCard day={day} />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

const rows = () => screen.getAllByTestId('agenda-row')

describe('AgendaCard', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'test-jwt')
    // Only the clock is frozen: the card reads « now » from it, and the fetch promises keep running.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(NOW))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('lists the day in order, with the « maintenant » line between what is done and what follows', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(today([stay, past, running, upcoming, evening]))))

    renderCard()

    await screen.findByText('Dentiste')
    expect(fetch).toHaveBeenCalledWith('/api/home/agenda?day=today', expect.anything())
    expect(rows().map((row) => row.getAttribute('data-kind'))).toEqual(['plain', 'past', 'current', 'next', 'later'])
    expect(rows().map((row) => within(row).getByText(/Séjour|Réunion|Atelier|Dentiste|Yoga/).textContent)).toEqual([
      'Séjour',
      'Réunion équipe · fait',
      'Atelier',
      'Dentiste',
      'Yoga',
    ])

    const line = screen.getByTestId('agenda-now-line')
    expect(line).toHaveTextContent('maintenant · 14:12')
    const [, donePast, inProgress] = rows()
    expect(donePast.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(line.compareDocumentPosition(inProgress) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  test('dims the past rows but keeps them readable, and says « dans 48 min » on the next one', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(today([past, upcoming]))))

    renderCard()

    await screen.findByText('Dentiste')
    const [done, next] = rows()
    expect(done).toHaveTextContent('09:00')
    expect(done).toHaveTextContent('Réunion équipe · fait')
    expect(done).toHaveTextContent('Salle 2')
    expect(done).toBeVisible()
    expect(getComputedStyle(done).opacity).toBe('0.6')
    expect(getComputedStyle(next).opacity).toBe('1')
    expect(next).toHaveTextContent('15:00')
    expect(next).toHaveTextContent('Cabinet Martin · dans 48 min')
  })

  test('says « en cours » on an event that has begun and is not over', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(today([running, upcoming]))))

    renderCard()

    await screen.findByText('Atelier')
    expect(rows()[0]).toHaveTextContent('en cours')
    expect(rows()[1]).toHaveTextContent('dans 48 min')
  })

  test('shows the whole of tomorrow with no line, nothing done and nothing highlighted', async () => {
    const tomorrow = { date: '2026-10-15', now: null, events: [past, upcoming] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(tomorrow)))

    renderCard('tomorrow')

    await screen.findByText('Dentiste')
    expect(fetch).toHaveBeenCalledWith('/api/home/agenda?day=tomorrow', expect.anything())
    expect(screen.queryByTestId('agenda-now-line')).not.toBeInTheDocument()
    expect(screen.queryByText(/fait/)).not.toBeInTheDocument()
    expect(screen.queryByText(/dans \d+/)).not.toBeInTheDocument()
    expect(rows().map((row) => row.getAttribute('data-kind'))).toEqual(['plain', 'plain'])
  })

  test('says so when there is nothing planned', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(today([]))))

    renderCard()

    expect(await screen.findByText("Rien de prévu aujourd'hui.")).toBeInTheDocument()
    expect(screen.queryByTestId('agenda-row')).not.toBeInTheDocument()
  })

  test('touching a row opens the fiche of the event, with its fields', async () => {
    vi.useRealTimers()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(today([upcoming], '2026-10-14T14:12:00+02:00'))))
    const user = userEvent.setup()

    renderCard()
    await user.click(await screen.findByRole('button', { name: /Dentiste/ }))

    const fiche = await screen.findByTestId('agenda-event-panel')
    expect(within(fiche).getByRole('heading', { name: 'Dentiste' })).toBeInTheDocument()
    expect(fiche).toHaveTextContent('Mercredi 14 octobre, 15:00 – 16:00')
    expect(fiche).toHaveTextContent('Cabinet Martin, 15 rue de la Paix')
    expect(fiche).toHaveTextContent('Perso')
    expect(fiche).toHaveTextContent('Contrôle annuel')
    expect(fiche).toHaveTextContent('30 minutes avant')
    expect(within(fiche).getByRole('link', { name: "Ouvrir dans l'agenda" })).toHaveAttribute(
      'href',
      '/calendar?eventId=%2Fapi%2Fevents%2Fupcoming',
    )
    expect(within(fiche).queryByRole('link', { name: 'Lancer' })).not.toBeInTheDocument()

    await user.click(within(fiche).getByRole('button', { name: 'Fermer' }))

    await waitFor(() => expect(screen.queryByTestId('agenda-event-panel')).not.toBeInTheDocument())
  })

  test('the fiche of an activity event offers « Lancer », which opens the activity and remembers where it came from', async () => {
    vi.useRealTimers()
    const review = event('review', 'Revue', '2026-10-14T18:00:00+02:00', '2026-10-14T19:00:00+02:00', { activity: 'finance-review' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(today([review]))))
    const user = userEvent.setup()

    renderCard()
    await user.click(await screen.findByTestId('agenda-row'))
    await user.click(await screen.findByRole('link', { name: 'Lancer' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/review/activity|{"from":"/"}')
  })

  test('the fiche of a whole-day event shows its days', async () => {
    vi.useRealTimers()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(today([stay]))))
    const user = userEvent.setup()

    renderCard()
    await user.click(await screen.findByTestId('agenda-row'))

    expect(await screen.findByTestId('agenda-event-panel')).toHaveTextContent('Mardi 13 octobre – Jeudi 15 octobre')
  })

  test('reads the day again when the Agenda publishes on home/agenda, and the open fiche follows', async () => {
    vi.useRealTimers()
    const moved = { ...upcoming, summary: 'Dentiste (déplacé)' }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(answer(today([upcoming])))
      .mockResolvedValueOnce(answer(today([moved])))
      .mockResolvedValueOnce(answer(today([])))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderCard()
    await user.click(await screen.findByTestId('agenda-row'))
    await screen.findByTestId('agenda-event-panel')

    await act(async () => {
      await emitMercure('/home/agenda', {})
    })
    await waitFor(() => expect(within(screen.getByTestId('agenda-event-panel')).getByRole('heading')).toHaveTextContent('Dentiste (déplacé)'))

    await act(async () => {
      await emitMercure('/home/agenda', {})
    })
    // Deleted meanwhile: the fiche has nothing to show and closes.
    await waitFor(() => expect(screen.queryByTestId('agenda-event-panel')).not.toBeInTheDocument())
    expect(screen.getByText("Rien de prévu aujourd'hui.")).toBeInTheDocument()
  })

  test('moves the line on with the clock, without asking the server again', async () => {
    vi.useRealTimers()
    vi.useFakeTimers()
    vi.setSystemTime(new Date(NOW))
    const fetchMock = vi.fn().mockResolvedValue(answer(today([upcoming, evening])))
    vi.stubGlobal('fetch', fetchMock)

    renderCard()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getByTestId('agenda-now-line')).toHaveTextContent('maintenant · 14:12')
    expect(rows()[0]).toHaveAttribute('data-kind', 'next')

    // 16:05: the Dentiste is over, Yoga is the next one.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(113 * 60_000)
    })

    expect(screen.getByTestId('agenda-now-line')).toHaveTextContent('maintenant · 16:05')
    expect(rows().map((row) => row.getAttribute('data-kind'))).toEqual(['past', 'next'])
    expect(rows()[1]).toHaveTextContent('dans 1 h 55')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  test('says it could not load, and tries again on demand', async () => {
    vi.useRealTimers()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 500, json: () => Promise.resolve({}) })
      .mockResolvedValueOnce(answer(today([upcoming])))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderCard()
    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByText('Dentiste')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('the title opens the Agenda', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(today([]))))
    renderCard()
    await screen.findByText("Rien de prévu aujourd'hui.")

    expect(screen.getByRole('link', { name: 'Agenda' })).toHaveAttribute('href', '/calendar')
  })
})
