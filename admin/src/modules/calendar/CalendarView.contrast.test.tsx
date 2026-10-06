import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { CalendarView } from './CalendarView'

/**
 * MAG-252: the text of a coloured bar follows its background.
 *
 * FullCalendar writes white on every event unless it is told otherwise, which made
 * a multi-day event on a yellow agenda unreadable. The bars below are all-day, so
 * the month grid draws them as filled blocks — the only kind whose text sits on the
 * agenda's own colour.
 */

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

vi.mock('react-router-dom', () => ({
  useSearchParams: () => [new URLSearchParams(), vi.fn()],
}))

const mockGetList = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  }),
  useNotify: () => vi.fn(),
}))

const DARK_TEXT = 'rgba(0, 0, 0, 0.87)'
const LIGHT_TEXT = 'rgb(255, 255, 255)'

const AGENDAS = [
  { id: '/api/agendas/01JAUNE', name: 'Jaune', color: '#FDD663', default: true },
  { id: '/api/agendas/01ROSE', name: 'Rose', color: '#F8BBD0', default: false },
  { id: '/api/agendas/01VERT', name: 'Vert', color: '#A8DAB5', default: false },
  { id: '/api/agendas/01BLEU', name: 'Bleu', color: '#1a73e8', default: false },
  { id: '/api/agendas/01VIOLET', name: 'Violet', color: '#7b1fa2', default: false },
  { id: '/api/agendas/01ROUGE', name: 'Rouge', color: '#c62828', default: false },
]

const noon = new Date()
noon.setHours(12, 0, 0, 0)
noon.setDate(15)
const localDay = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const day = (offset: number) => new Date(noon.getTime() + offset * 86_400_000).toISOString()

const allDay = (id: string, summary: string, agenda: string, extra: Record<string, unknown> = {}) => ({
  id,
  summary,
  startAt: day(0),
  endAt: day(2),
  allDay: true,
  agenda,
  ...extra,
})

const EVENTS = [
  allDay('ev-jaune', 'Meven', '/api/agendas/01JAUNE'),
  allDay('ev-rose', 'Anniversaire', '/api/agendas/01ROSE'),
  allDay('ev-vert', 'Randonnée', '/api/agendas/01VERT'),
  allDay('ev-bleu', 'Séminaire', '/api/agendas/01BLEU'),
  allDay('ev-violet', 'Voyage', '/api/agendas/01VIOLET'),
  allDay('ev-rouge', 'Déménagement', '/api/agendas/01ROUGE'),
]

const barOf = (title: string) => {
  const bar = screen.getAllByText(title)[0].closest('.fc-event') as HTMLElement | null
  expect(bar, `no event bar for "${title}"`).not.toBeNull()

  return bar!
}

// FullCalendar writes the text colour on the bar's `.fc-event-main`, not on the bar itself.
const textColorOf = (title: string) => {
  const main = barOf(title).querySelector('.fc-event-main') as HTMLElement

  return getComputedStyle(main).color
}

describe('CalendarView — readable text on coloured bars', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const serve = (data: { events?: unknown[]; tasks?: unknown[]; meals?: unknown[] }) => {
    mockGetList.mockImplementation((resource: string) => {
      const rows: unknown[] =
        'agendas' === resource ? AGENDAS : (data[resource as 'events' | 'tasks' | 'meals'] ?? [])

      return Promise.resolve({ data: rows, total: rows.length })
    })
  }

  test('a light agenda gets dark text, a dark agenda gets white text', async () => {
    serve({ events: EVENTS })
    render(<CalendarView />)

    await screen.findAllByText('Meven')

    for (const title of ['Meven', 'Anniversaire', 'Randonnée']) {
      expect(textColorOf(title), title).toBe(DARK_TEXT)
    }
    for (const title of ['Séminaire', 'Voyage', 'Déménagement']) {
      expect(textColorOf(title), title).toBe(LIGHT_TEXT)
    }
  })

  test('a recurring occurrence and its modified exception follow their agenda too', async () => {
    const master = {
      id: '/api/events/01SERIE',
      summary: 'Cours',
      startAt: noon.toISOString(),
      endAt: new Date(noon.getTime() + 3600_000).toISOString(),
      allDay: false,
      agenda: '/api/agendas/01JAUNE',
      rrule: 'FREQ=DAILY;COUNT=4',
    }
    const exception = {
      id: '/api/events/01EXC',
      summary: 'Cours déplacé',
      startAt: day(1),
      endAt: day(2),
      allDay: true,
      agenda: '/api/agendas/01JAUNE',
      recurringEvent: master.id,
      originalStartAt: day(1),
      status: 'confirmed',
    }
    serve({ events: [master, exception, allDay('ev-fond', 'Séminaire', '/api/agendas/01BLEU')] })
    render(<CalendarView />)

    await screen.findAllByText('Cours déplacé')

    expect(textColorOf('Cours déplacé')).toBe(DARK_TEXT)
    expect(textColorOf('Séminaire')).toBe(LIGHT_TEXT)
  })

  test('tasks, done tasks and meals are readable on their own colour', async () => {
    const task = (id: string, title: string, criticality: string, done = false) => ({
      id,
      title,
      priority: 'low',
      criticality,
      dueDate: noon.toISOString(),
      completedAt: done ? noon.toISOString() : null,
    })
    serve({
      tasks: [
        task('t-low', 'Tâche faible', 'low'),
        task('t-critical', 'Tâche critique', 'critical'),
        task('t-done', 'Tâche finie', 'critical', true),
      ],
      meals: [{ id: 'm1', date: localDay(noon), slot: 'lunch', summary: 'Pâtes', recipes: [] }],
    })
    render(<CalendarView />)

    await waitFor(() => expect(screen.getAllByText(/Tâche faible/).length).toBeGreaterThan(0))

    expect(textColorOf('Tâche faible')).toBe(DARK_TEXT)
    expect(textColorOf('Tâche critique')).toBe(LIGHT_TEXT)
    expect(textColorOf('✓ Tâche finie')).toBe(DARK_TEXT)
    expect(textColorOf('Déj: Pâtes')).toBe(DARK_TEXT)
  })
})
