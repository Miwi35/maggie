import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { render } from '@testing-library/react'
import { AdminContext } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../../theme'
import { DigestPage } from './DigestPage'
import type { AgendaHomeState } from '../../home/cards/agendaTimeline'
import type { TasksHomeState } from '../../home/cards/tasksCardState'
import type { CuisineHomeState } from '../../cookbook/cuisineCardState'
import type { HealthHomeState } from '../../home/cards/healthCardState'

const { read, hook } = vi.hoisted(() => {
  const read = { agenda: null as unknown, tasks: null as unknown, cuisine: null as unknown, health: null as unknown }
  const hook = (key: keyof typeof read) => () => {
    const value = read[key]

    return value === 'error'
      ? { state: null, loading: false, error: true, retry: () => undefined }
      : { state: value, loading: false, error: false, retry: () => undefined }
  }

  return { read, hook }
})
vi.mock('../../home/useAgendaCardState', () => ({ useAgendaCardState: hook('agenda') }))
vi.mock('../../home/useTasksCardState', () => ({ useTasksCardState: hook('tasks') }))
vi.mock('../../cookbook/useCuisineCardState', () => ({ useCuisineCardState: hook('cuisine') }))
vi.mock('../../home/useHealthCardState', () => ({ useHealthCardState: hook('health') }))

const notify = vi.hoisted(() => vi.fn())
vi.mock('react-admin', async (original) => ({ ...(await original<typeof import('react-admin')>()), useNotify: () => notify }))

const AUDIO = { type: 'audio/mpeg', durationMs: 72_000, size: 10, url: '/agent/messages/D1/audio', transcript: 'Bonjour' }
const block = (openedAt: string | null) => ({ type: 'digest', day: '2026-10-27', text: '[warmly] Il fait beau. Kiné à 14 h 30.', expiresAt: '2026-10-27T14:00:00', openedAt })
const message = (openedAt: string | null) => ({ id: 'D1', content: 'Votre digest du matin est disponible.', audio: AUDIO, blocks: [block(openedAt)] })
const WEATHER = { minC: 5, maxC: 14.2, rainMm: 4.3, rainProbability: 20, windKmh: 15, condition: 'partly_cloudy', alerts: ['Vent fort'] }

class FakeAudio extends EventTarget {
  currentTime = 0
  playbackRate = 1
  play = vi.fn(() => Promise.resolve())
  pause = vi.fn()
  static instances: FakeAudio[] = []
  constructor(public src: string) {
    super()
    FakeAudio.instances.push(this)
  }
}

const agenda: AgendaHomeState = {
  date: '2026-10-27',
  now: null,
  trips: [{ id: 'T1', event: '/api/events/E1', mode: 'bicycle', steps: [], durationMinutes: 15, departAt: '2026-10-27T09:05:00+01:00', navigationUrl: '', originLabel: 'maison' }],
  events: [
    { id: 'E1', summary: 'Kiné', description: null, location: null, locationShort: null, allDay: false, startAt: '2026-10-27T09:30:00+01:00', endAt: '2026-10-27T10:00:00+01:00', timeZone: 'Europe/Paris', status: 'confirmed', reminders: null, activity: null, agendaId: 'A', agendaName: 'Perso', agendaColor: null },
  ],
}
const tasks = {
  module: 'tasks', day: 'today', editable: true, done: 0, total: 1, more: 0, overdue: 0, routines: [], saga: null,
  tasks: [{ id: 'K1', title: 'Appeler le notaire', done: false, overdueDays: null, parentId: null, urgency: 'high', scheduledMoment: 'evening' }],
} as TasksHomeState
const cuisine: CuisineHomeState = {
  slots: [
    { slot: 'lunch', meals: [{ id: 'M1', title: 'Gratin de courgettes', guests: 2, recipes: [], missing: [] }] },
    { slot: 'dinner', meals: [] },
  ],
}
const health: HealthHomeState = {
  day: 'today', steps: null, balance: null,
  workout: { id: 'W1', title: 'Course', time: '18:30', durationMinutes: 30 },
  fast: { state: 'fasting', windowStart: '12:00', windowEnd: '20:00', minutesToEdge: 60, progress: 0.5 },
}

let posts: string[]
let digest: unknown
let weather: unknown
let failRead: boolean

beforeEach(() => {
  posts = []
  digest = message(null)
  weather = WEATHER
  failRead = false
  read.agenda = agenda
  read.tasks = tasks
  read.cuisine = cuisine
  read.health = health
  FakeAudio.instances = []
  notify.mockReset()
  vi.stubGlobal('Audio', FakeAudio)
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const reply = (status: number, body: unknown = null) => Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) } as Response)
      if (init?.method === 'POST') {
        posts.push(url)
        return reply(200, {})
      }
      if (failRead && url.includes('/agent/messages')) return reply(500)
      if (url.includes('/agent/messages/context')) return reply(200, { messages: digest ? [digest] : [] })
      if (url.includes('screen=digest')) return reply(200, digest ? [digest] : [])
      if (url.includes('/api/weather')) return weather ? reply(200, weather) : reply(404)
      if (url.includes('/audio')) return reply(200, { url: 'https://bucket/a.mp3' })
      return reply(404)
    }),
  )
})
afterEach(() => vi.unstubAllGlobals())

const Where = () => <span data-testid="where">{useLocation().pathname}</span>

const renderPage = (route = '/digest?message=D1', theme = veilleuseDarkTheme) =>
  render(
    <MemoryRouter initialEntries={[route]}>
      <AdminContext theme={theme} defaultTheme="light">
        <Routes>
          <Route path="/digest" element={<DigestPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

describe('DigestPage', () => {
  it('shows the trail, the date, the greeting, the voice and the word', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { level: 1, name: 'Mardi 27 octobre' })).toBeInTheDocument()
    expect(screen.getByText('Bonjour monsieur')).toBeInTheDocument()
    expect(screen.getByTestId('voice-bubble')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Transcription' })).not.toBeInTheDocument()
    const word = screen.getByTestId('digest-word')
    expect(word).toHaveTextContent('Il fait beau. Kiné à 14 h 30.')
    expect(word.textContent).not.toMatch(/\[/)
  })

  it("draws the cards in the mock-up's order with its labels", async () => {
    renderPage()
    await screen.findByTestId('digest-weather')
    const titles = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(titles).toEqual(['● Météo', 'Agenda', 'Tâches', 'Cuisine', 'Santé'])
    expect(screen.getByTestId('digest-weather-max')).toHaveTextContent('14°')
    const weatherCard = screen.getByTestId('digest-weather')
    for (const text of ['min 5°', 'Éclaircies', '4 mm de pluie', 'Vent fort', 'pluie 20 %', 'vent 15 km/h']) {
      expect(within(weatherCard).getByText(text)).toBeInTheDocument()
    }
    const agendaCard = screen.getByTestId('home-card-agenda')
    expect(agendaCard).toHaveTextContent('09:05')
    expect(agendaCard).toHaveTextContent('Départ pour kiné · 15 min à vélo')
    expect(agendaCard).toHaveTextContent('09:30')
    expect(within(screen.getByTestId('home-card-tasks')).getByText('Haute')).toBeInTheDocument()
    expect(within(screen.getByTestId('home-card-tasks')).getByText('ce soir')).toBeInTheDocument()
    expect(screen.getByTestId('home-card-cuisine')).toHaveTextContent('Gratin de courgettes')
    expect(screen.getByTestId('home-card-cuisine')).toHaveTextContent('midi')
    expect(screen.getByTestId('home-card-health')).toHaveTextContent('Course · 18:30')
    expect(screen.getByTestId('home-card-health')).toHaveTextContent('Jeûne → 12:00')
    expect(screen.getByTestId('home-card-health')).toHaveTextContent('16:8')
  })

  it('a source that fails or is empty leaves its card out', async () => {
    weather = null
    read.agenda = 'error'
    read.tasks = { ...tasks, tasks: [] }
    read.cuisine = { slots: [] }
    renderPage()
    await screen.findByText('Bonjour monsieur')
    await screen.findByTestId('home-card-health')
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Santé'])
  })

  it('the first opening marks it opened once and plays', async () => {
    renderPage()
    await screen.findByRole('button', { name: 'Pause' })
    expect(posts).toEqual(['/agent/messages/D1/digest/opened'])
    expect(FakeAudio.instances[0].play).toHaveBeenCalledTimes(1)
  })

  it('a second opening marks nothing and does not play', async () => {
    digest = message('2026-10-27T07:05:00Z')
    renderPage()
    await screen.findByText('Bonjour monsieur')
    expect(screen.getByRole('button', { name: 'Lecture' })).toBeInTheDocument()
    expect(posts).toEqual([])
    expect(FakeAudio.instances).toHaveLength(0)
  })

  it('a card opens its module', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('link', { name: /Agenda/ }))
    expect(screen.getByTestId('where')).toHaveTextContent('/calendar')
  })

  it('no digest says so', async () => {
    digest = null
    renderPage('/digest')
    expect(await screen.findByText('Pas de digest ce matin.')).toBeInTheDocument()
  })

  it('the latest digest opens without an id', async () => {
    renderPage('/digest')
    expect(await screen.findByRole('heading', { level: 1, name: 'Mardi 27 octobre' })).toBeInTheDocument()
  })

  it('a failed read notifies in French', async () => {
    failRead = true
    renderPage()
    await waitFor(() => expect(notify).toHaveBeenCalledWith("Le digest n'a pas pu être chargé.", { type: 'error' }))
  })

  it.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ])('veilleuse %s', async (_mode, theme) => {
    renderPage('/digest?message=D1', theme)
    expect(await screen.findByTestId('digest-weather')).toBeInTheDocument()
  })
})
