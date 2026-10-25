import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../../theme'
import { emitMercure } from '../../../test/mercure'
import { AgendaCard } from './AgendaCard'
import { localMidnightIso } from './tasksCardState'
import type { ApiEvent } from './agendaCardState'
import type { HomeDay } from '../homeDay'

vi.mock('../../../hooks/useMercure', async () => (await import('../../../test/mercure')).mercureModule)

const NOW = '2026-10-14T14:12:00+02:00'

const AGENDAS = [{ id: 'agenda-1', name: 'Perso', color: '#4285F4' }]

const event = (id: string, summary: string, startAt: string, endAt: string, extra: Partial<ApiEvent> = {}): ApiEvent => ({
  id,
  summary,
  allDay: false,
  startAt,
  endAt,
  timeZone: 'Europe/Paris',
  status: 'confirmed',
  agenda: '/api/agendas/agenda-1',
  ...extra,
})

const stay: ApiEvent = {
  id: 'stay',
  summary: 'Séjour',
  allDay: true,
  startDate: '2026-10-13',
  endDate: '2026-10-16',
  timeZone: 'Europe/Paris',
  status: 'confirmed',
  agenda: '/api/agendas/agenda-1',
}
const past = event('past', 'Réunion équipe', '2026-10-14T09:00:00+02:00', '2026-10-14T10:00:00+02:00', { location: 'Salle 2' })
const running = event('running', 'Atelier', '2026-10-14T13:30:00+02:00', '2026-10-14T14:30:00+02:00')
const upcoming = event('upcoming', 'Dentiste', '2026-10-14T15:00:00+02:00', '2026-10-14T16:00:00+02:00', {
  description: 'Contrôle annuel',
  location: 'Cabinet Martin, 15 rue de la Paix',
  reminders: { useDefault: false, overrides: [{ method: 'popup', minutes: 30 }] },
})
const evening = event('evening', 'Yoga', '2026-10-14T18:00:00+02:00', '2026-10-14T19:00:00+02:00')

const ok = (member: unknown[]) => ({ ok: true, json: () => Promise.resolve({ member }) })

/** The two resources the card reads: the events of the period, then the agendas. */
const reads = (events: ApiEvent[]) => (url: string) => Promise.resolve(url.includes('/agendas') ? ok(AGENDAS) : ok(events))

const eventsUrl = (mock: ReturnType<typeof vi.fn>): URL => {
  const url = mock.mock.calls.map(([u]) => String(u)).find((u) => u.includes('/events?'))

  return new URL(url ?? 'http://none')
}

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
    vi.stubGlobal('fetch', vi.fn(reads([stay, past, running, upcoming, evening])))

    renderCard()

    await screen.findByText('Dentiste')
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

  test('reads the day from the events and the agendas: two requests, the series unfolded by the API', async () => {
    const fetchMock = vi.fn(reads([upcoming]))
    vi.stubGlobal('fetch', fetchMock)

    renderCard()

    await screen.findByText('Dentiste')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const params = eventsUrl(fetchMock).searchParams
    expect(params.get('expand')).toBe('true')
    expect(params.get('pagination')).toBe('false')
    expect(params.get('endAt[after]')).toBe(localMidnightIso('2026-10-14'))
    expect(params.get('startAt[before]')).toBe(localMidnightIso('2026-10-15'))
    expect(fetchMock.mock.calls.map(([u]) => String(u)).some((u) => u.includes('/agendas'))).toBe(true)
  })

  test('dims the past rows but keeps them readable, and says « dans 48 min » on the next one', async () => {
    vi.stubGlobal('fetch', vi.fn(reads([past, upcoming])))

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
    vi.stubGlobal('fetch', vi.fn(reads([running, upcoming])))

    renderCard()

    await screen.findByText('Atelier')
    expect(rows()[0]).toHaveTextContent('en cours')
    expect(rows()[1]).toHaveTextContent('dans 48 min')
  })

  test('shows the whole of tomorrow with no line, nothing done and nothing highlighted', async () => {
    const fetchMock = vi.fn(reads([past, upcoming]))
    vi.stubGlobal('fetch', fetchMock)

    renderCard('tomorrow')

    await screen.findByText('Dentiste')
    const params = eventsUrl(fetchMock).searchParams
    expect(params.get('endAt[after]')).toBe(localMidnightIso('2026-10-15'))
    expect(params.get('startAt[before]')).toBe(localMidnightIso('2026-10-16'))
    expect(screen.queryByTestId('agenda-now-line')).not.toBeInTheDocument()
    expect(screen.queryByText(/fait/)).not.toBeInTheDocument()
    expect(screen.queryByText(/dans \d+/)).not.toBeInTheDocument()
    expect(rows().map((row) => row.getAttribute('data-kind'))).toEqual(['plain', 'plain'])
  })

  test('is not drawn at all when there is nothing planned', async () => {
    vi.stubGlobal('fetch', vi.fn(reads([])))

    renderCard()
    expect(screen.getByLabelText("Chargement de l'agenda")).toBeInTheDocument()

    await waitFor(() => expect(screen.queryByTestId('home-card-agenda')).not.toBeInTheDocument())
    expect(screen.queryByText(/Rien de prévu/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Agenda' })).not.toBeInTheDocument()
  })

  test('touching a row opens the fiche of the event, with its fields', async () => {
    vi.useRealTimers()
    vi.stubGlobal('fetch', vi.fn(reads([upcoming])))
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

  describe('« ▶ Lancer » on the line of an activity (MAG-568)', () => {
    const review = event('review', "Revue d'octobre", '2026-10-14T20:00:00+02:00', '2026-10-14T21:00:00+02:00', { activity: 'finance-review' })

    test('the review line carries a Lancer today, which opens the activity and remembers where it came from', async () => {
      vi.useRealTimers()
      vi.stubGlobal('fetch', vi.fn(reads([upcoming, review])))
      const user = userEvent.setup()

      renderCard()
      await screen.findByText("Revue d'octobre")

      expect(screen.getAllByTestId('home-launch-finance')).toHaveLength(1)
      await user.click(screen.getByRole('link', { name: "Lancer Revue d'octobre" }))

      expect(screen.getByTestId('where')).toHaveTextContent('/finance/review/activity|{"from":"/"}')
    })

    test('an event that is not an activity has none, and touching the ▶ does not open the fiche', async () => {
      vi.useRealTimers()
      vi.stubGlobal('fetch', vi.fn(reads([upcoming, review])))
      const user = userEvent.setup()

      renderCard()
      await screen.findByText('Dentiste')

      expect(screen.queryByRole('link', { name: 'Lancer Dentiste' })).not.toBeInTheDocument()
      await user.click(screen.getByRole('link', { name: "Lancer Revue d'octobre" }))
      expect(screen.queryByTestId('agenda-event-panel')).not.toBeInTheDocument()
    })

    test('tomorrow has none', async () => {
      vi.stubGlobal('fetch', vi.fn(reads([upcoming, review])))

      renderCard('tomorrow')

      await screen.findByText("Revue d'octobre")
      expect(screen.queryByTestId('home-launch-finance')).not.toBeInTheDocument()
    })
  })

  test('the fiche of an activity event offers « Lancer », which opens the activity and remembers where it came from', async () => {
    vi.useRealTimers()
    const review = event('review', 'Revue', '2026-10-14T18:00:00+02:00', '2026-10-14T19:00:00+02:00', { activity: 'finance-review' })
    vi.stubGlobal('fetch', vi.fn(reads([review])))
    const user = userEvent.setup()

    renderCard()
    await user.click(await screen.findByTestId('agenda-row'))
    await user.click(await screen.findByRole('link', { name: 'Lancer' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/review/activity|{"from":"/"}')
  })

  test('the review line carries its own « ▶ Lancer », which opens the activity; the other lines do not', async () => {
    const review = event('review', 'Revue', '2026-10-14T23:00:00+02:00', '2026-10-14T23:30:00+02:00', { activity: 'finance-review' })
    vi.stubGlobal('fetch', vi.fn(reads([review, upcoming])))
    const user = userEvent.setup()

    renderCard()
    await screen.findByText('Dentiste')

    const launches = screen.getAllByTestId('home-launch-finance')
    expect(launches).toHaveLength(1)
    expect(launches[0]).toHaveAccessibleName('Lancer Revue')
    // Beside the row's button, not inside it: a link in a button is not valid.
    expect(within(screen.getAllByTestId('agenda-row')[0]).queryByTestId('home-launch-finance')).not.toBeInTheDocument()

    await user.click(launches[0])
    expect(screen.getByTestId('where')).toHaveTextContent('/finance/review/activity|{"from":"/"}')
  })

  test('has no « ▶ Lancer » on « Demain »', async () => {
    const review = event('review', 'Revue', '2026-10-15T18:00:00+02:00', '2026-10-15T19:00:00+02:00', { activity: 'finance-review' })
    vi.stubGlobal('fetch', vi.fn(reads([review])))

    renderCard('tomorrow')
    await screen.findByTestId('agenda-row')

    expect(screen.queryByTestId('home-launch-finance')).not.toBeInTheDocument()
  })

  test('« Voir la journée › » opens the Agenda', async () => {
    vi.stubGlobal('fetch', vi.fn(reads([upcoming])))

    renderCard()

    expect(await screen.findByRole('link', { name: 'Voir la journée ›' })).toHaveAttribute('href', '/calendar')
  })

  test('the fiche of a whole-day event shows its days', async () => {
    vi.useRealTimers()
    vi.stubGlobal('fetch', vi.fn(reads([stay])))
    const user = userEvent.setup()

    renderCard()
    await user.click(await screen.findByTestId('agenda-row'))

    expect(await screen.findByTestId('agenda-event-panel')).toHaveTextContent('Mardi 13 octobre – Jeudi 15 octobre')
  })

  test('reads the day again when an event is published, and the open fiche follows', async () => {
    vi.useRealTimers()
    let current = [upcoming]
    vi.stubGlobal('fetch', vi.fn((url: string) => reads(current)(url)))
    const user = userEvent.setup()

    renderCard()
    await user.click(await screen.findByTestId('agenda-row'))
    await screen.findByTestId('agenda-event-panel')

    current = [{ ...upcoming, summary: 'Dentiste (déplacé)' }]
    await act(async () => {
      await emitMercure('/api/events/{id}', { '@id': '/api/events/upcoming' })
    })
    await waitFor(() => expect(within(screen.getByTestId('agenda-event-panel')).getByRole('heading')).toHaveTextContent('Dentiste (déplacé)'))

    current = []
    await act(async () => {
      await emitMercure('/api/events/{id}', { '@id': '/api/events/upcoming' })
    })
    // Deleted meanwhile: the fiche has nothing to show and closes.
    await waitFor(() => expect(screen.queryByTestId('agenda-event-panel')).not.toBeInTheDocument())
    // The last event gone, the card is gone with it.
    expect(screen.queryByTestId('home-card-agenda')).not.toBeInTheDocument()
  })

  test('reads the day again when an agenda is renamed, and the fiche says the new name', async () => {
    vi.useRealTimers()
    let agendas = AGENDAS
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => Promise.resolve(url.includes('/agendas') ? ok(agendas) : ok([upcoming]))),
    )
    const user = userEvent.setup()

    renderCard()
    await user.click(await screen.findByTestId('agenda-row'))
    expect(await screen.findByTestId('agenda-event-panel')).toHaveTextContent('Perso')

    agendas = [{ id: 'agenda-1', name: 'Famille', color: '#0F9D58' }]
    await act(async () => {
      await emitMercure('/api/agendas/{id}', { '@id': '/api/agendas/agenda-1' })
    })

    await waitFor(() => expect(screen.getByTestId('agenda-event-panel')).toHaveTextContent('Famille'))
  })

  test('moves the line on with the clock, without asking the server again', async () => {
    vi.useRealTimers()
    vi.useFakeTimers()
    vi.setSystemTime(new Date(NOW))
    const fetchMock = vi.fn(reads([upcoming, evening]))
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
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  test('says it could not load, and tries again on demand', async () => {
    vi.useRealTimers()
    let failing = true
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve(failing ? { ok: false, status: 500, json: () => Promise.resolve({}) } : reads([upcoming])(url)),
      ),
    )
    const user = userEvent.setup()

    renderCard()
    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    failing = false
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByText('Dentiste')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('an agendas read that fails fails the card, rather than drawing events with no agenda', async () => {
    vi.useRealTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        Promise.resolve(url.includes('/agendas') ? { ok: false, status: 500, json: () => Promise.resolve({}) } : ok([upcoming])),
      ),
    )

    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    expect(screen.queryByText('Dentiste')).not.toBeInTheDocument()
  })

  test('a module that is not there answers 404: the card is not drawn and says nothing', async () => {
    vi.useRealTimers()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: () => Promise.resolve({}) }))

    renderCard()

    await waitFor(() => expect(screen.queryByTestId('home-card-agenda')).not.toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('the title opens the Agenda', async () => {
    vi.stubGlobal('fetch', vi.fn(reads([upcoming])))
    renderCard()
    await screen.findByTestId('agenda-row')

    expect(screen.getByRole('link', { name: 'Agenda' })).toHaveAttribute('href', '/calendar')
  })
})
