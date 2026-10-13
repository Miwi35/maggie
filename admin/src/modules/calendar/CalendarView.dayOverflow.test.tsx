import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { forwardRef } from 'react'
import { render, screen } from '@testing-library/react'
import { CalendarView } from './CalendarView'

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

vi.mock('react-router-dom', () => ({
  useSearchParams: () => [new URLSearchParams(), vi.fn()],
}))

const renderedWith = vi.hoisted(() => vi.fn())
vi.mock('@fullcalendar/react', async (importOriginal) => {
  const { default: Original } = await importOriginal<typeof import('@fullcalendar/react')>()
  const Spy = forwardRef<unknown, Record<string, unknown>>((props, ref) => {
    renderedWith(props)
    return <Original ref={ref as never} {...(props as object)} />
  })
  Spy.displayName = 'FullCalendarSpy'
  return { default: Spy }
})

const mockGetList = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    getOne: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  }),
  useNotify: () => vi.fn(),
}))

const AGENDA = '/api/agendas/01PERSO'

const timed = (id: string, summary: string, hour: number) => ({
  id: `/api/events/${id}`,
  summary,
  allDay: false,
  startAt: `2037-01-08T${String(hour).padStart(2, '0')}:00:00+01:00`,
  endAt: `2037-01-08T${String(hour + 1).padStart(2, '0')}:00:00+01:00`,
  agenda: AGENDA,
})

const SEVERAL_DAYS = {
  id: '/api/events/01MEVEN',
  summary: 'Meven',
  allDay: true,
  startDate: '2037-01-07',
  endDate: '2037-01-10',
  startAt: null,
  endAt: null,
  agenda: AGENDA,
}

const EVENTS = [
  SEVERAL_DAYS,
  timed('01A', 'Dépose Ael', 8),
  timed('01B', 'Récupérer Ael', 16),
  timed('01C', 'Soirée à Rennes avec Julie', 20),
  timed('01D', 'Dentiste', 11),
]

/**
 * MAG-323: the month cell showed « +1 autre » with room for several more lines,
 * because it was capped at three events whatever its height. The cap is now the
 * cell's own height, which jsdom, doing no layout, cannot measure: the prop is
 * what is checked here, and the e2e journey checks the pixels (a crowded day
 * still gets its « +N autres »).
 */
describe('CalendarView — a month cell is not capped at a fixed number of events', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2037, 0, 15, 10, 0))
    vi.stubGlobal('EventSource', MockEventSource)
    mockGetList.mockImplementation((resource: string) => {
      if (resource === 'agendas') {
        return Promise.resolve({ data: [{ id: AGENDA, name: 'Perso', color: '#3f51b5', default: true }], total: 1 })
      }
      if (resource === 'events') return Promise.resolve({ data: EVENTS, total: EVENTS.length })
      return Promise.resolve({ data: [], total: 0 })
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('the month cell is capped by its height, not by a fixed number of events', async () => {
    render(<CalendarView />)
    await screen.findByText('Meven')

    expect(renderedWith).toHaveBeenLastCalledWith(expect.objectContaining({ dayMaxEvents: true }))
  })

  test('the events of a crowded day are all handed to the grid', async () => {
    render(<CalendarView />)

    for (const summary of ['Meven', 'Dépose Ael', 'Récupérer Ael', 'Soirée à Rennes avec Julie', 'Dentiste']) {
      expect(await screen.findByText(summary)).toBeInTheDocument()
    }
  })
})
