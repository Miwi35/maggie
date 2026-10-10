import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { addDays } from '../../dates'
import { Dashboard } from './Dashboard'

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

const mockGetList = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({ getList: mockGetList, update: vi.fn() }),
  useNotify: () => vi.fn(),
}))

/** An all-day event as the API sends it since MAG-382: two dates, the end excluded as in Google, no instant. */
const allDay = (id: string, summary: string, startDate: string, extra: Record<string, unknown> = {}) => ({
  id,
  summary,
  allDay: true,
  startDate,
  endDate: addDays(startDate, 1),
  startAt: null,
  endAt: null,
  agenda: '/api/agendas/01PERSO',
  ...extra,
})

const EVENTS = [
  allDay('01AUJ', 'Congé posé', '2037-01-15'),
  allDay('01DEMAIN', 'Pont de l’Ascension', '2037-01-16'),
  // A yearly birthday: expanded on its date, into this week.
  allDay('01SACHA', 'Anniversaire de Sacha', '2001-01-20', { rrule: 'FREQ=YEARLY' }),
  {
    id: '01DENTISTE',
    summary: 'Dentiste',
    allDay: false,
    startAt: new Date(2037, 0, 15, 14, 0).toISOString(),
    endAt: new Date(2037, 0, 15, 15, 0).toISOString(),
    startDate: null,
    endDate: null,
    agenda: '/api/agendas/01PERSO',
  },
]

/** The block under one of the dashboard's headings. */
const dailyColumn = (name: string) => screen.getByRole('heading', { name }).parentElement as HTMLElement
const digest = (name: string) => screen.getByRole('heading', { name }).parentElement!.parentElement as HTMLElement

describe('Dashboard — all-day events are dates (MAG-382)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2037, 0, 15, 10, 0))
    vi.stubGlobal('EventSource', MockEventSource)
    mockGetList.mockImplementation((resource: string, params: { filter: Record<string, unknown> }) => {
      if (resource === 'events' && params.filter['startAt[after]']) {
        return Promise.resolve({ data: EVENTS, total: EVENTS.length })
      }
      return Promise.resolve({ data: [], total: 0 })
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('places each all-day event on its own day, labelled « Journée »', async () => {
    render(<Dashboard />)
    await screen.findByText('Congé posé')

    const today = dailyColumn("Aujourd'hui")
    expect(within(today).getByText('Congé posé').closest('li')).toHaveTextContent('Journée')
    expect(within(today).getByText('Dentiste')).toBeInTheDocument()
    expect(within(today).queryByText('Pont de l’Ascension')).not.toBeInTheDocument()

    const tomorrow = dailyColumn('Demain')
    expect(within(tomorrow).getByText('Pont de l’Ascension')).toBeInTheDocument()
    expect(within(tomorrow).queryByText('Congé posé')).not.toBeInTheDocument()
  })

  test('puts this year’s birthday in the week, on its date', async () => {
    render(<Dashboard />)
    await screen.findByText('Anniversaire de Sacha')

    const week = digest('Cette semaine')
    expect(within(week).getByText('Anniversaire de Sacha').closest('li')).toHaveTextContent('20 janv.')
    expect(screen.getAllByText('Anniversaire de Sacha')).toHaveLength(1)
  })

  test('shows nothing to do when the events cannot be read', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    mockGetList.mockRejectedValue(new Error('500'))
    render(<Dashboard />)

    await waitFor(() => expect(consoleError).toHaveBeenCalled())
    expect(within(dailyColumn("Aujourd'hui")).getByText('Aucun événement')).toBeInTheDocument()
    consoleError.mockRestore()
  })
})
