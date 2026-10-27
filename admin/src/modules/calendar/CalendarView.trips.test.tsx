import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarView } from './CalendarView'

class MockEventSource {
  static instances: MockEventSource[] = []
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    MockEventSource.instances.push(this)
  }
}

vi.mock('react-router-dom', () => ({
  useSearchParams: () => [new URLSearchParams(), vi.fn()],
}))

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
const at = (hour: number, minute = 0) => new Date(2037, 0, 15, hour, minute)

const event = (id: string, summary: string, start: Date) => ({
  id: `/api/events/${id}`,
  summary,
  allDay: false,
  startAt: start.toISOString(),
  endAt: new Date(start.getTime() + 3_600_000).toISOString(),
  agenda: AGENDA,
})

const DENTIST = event('01DENTIST', 'Dentiste', at(14))
const SPORT = event('01SPORT', 'Sport', at(18))

const trip = (eventId: string, departAt: Date, extra: Record<string, unknown> = {}) => ({
  id: `/api/trips/01T${eventId}`,
  event: `/api/events/${eventId}`,
  mode: 'drive',
  steps: [
    { kind: 'walk', minutes: 4, label: 'À pied' },
    { kind: 'drive', minutes: 14, label: 'En voiture' },
    { kind: 'walk', minutes: 3, label: 'À pied' },
  ],
  durationMinutes: 21,
  departAt: departAt.toISOString(),
  navigationUrl: 'https://waze.com/ul?navigate=yes',
  originLabel: 'la maison',
  ...extra,
})

let trips: () => Promise<{ data: unknown[]; total: number }>
const serve = (events: unknown[], tripsList: () => unknown[] | Promise<never>) => {
  trips = () => Promise.resolve(tripsList() as unknown[]).then((data) => ({ data, total: data.length }))
  mockGetList.mockImplementation((resource: string) => {
    if (resource === 'agendas') {
      return Promise.resolve({ data: [{ id: AGENDA, name: 'Perso', color: '#3f51b5', default: true }], total: 1 })
    }
    if (resource === 'events') return Promise.resolve({ data: events, total: events.length })
    if (resource === 'trips') return trips()
    return Promise.resolve({ data: [], total: 0 })
  })
}

const openDayView = async () => {
  await userEvent.click(screen.getByRole('button', { name: 'Jour' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Jour' })).toHaveClass('MuiButton-contained'))
}

const send = (data: object) =>
  act(() => {
    MockEventSource.instances.forEach((es) => es.onmessage?.({ data: JSON.stringify(data) } as MessageEvent))  
  })

/** The trip line under an event in the day view (MAG-659). */
describe('CalendarView — trip line under the event', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    MockEventSource.instances = []
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(at(10))
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    localStorage.removeItem('user')
  })

  test('shows when to leave and the steps under the event it belongs to, and nothing under the others', async () => {
    serve([DENTIST, SPORT], () => [trip('01DENTIST', at(13, 34))])
    render(<CalendarView />)
    await openDayView()

    const line = await screen.findByText('Partir à 13:34 · depuis la maison')
    expect(line.closest('.fc-event')).toHaveTextContent('Dentiste')
    expect(line.closest('.fc-event')).toHaveTextContent('🚶 4 · 🚗 14 · 🚶 3')
    expect(screen.getAllByText(/Partir à/)).toHaveLength(1)
    expect(screen.getByText('Sport').closest('.fc-event')).not.toHaveTextContent('Partir à')
  })

  test('asks for the trips of the day shown, in the day view only', async () => {
    serve([DENTIST], () => [])
    render(<CalendarView />)
    await screen.findByText('Dentiste')
    expect(mockGetList.mock.calls.some(([resource]) => resource === 'trips')).toBe(false)

    await openDayView()

    await waitFor(() =>
      expect(mockGetList).toHaveBeenCalledWith('trips', expect.objectContaining({ filter: { date: '2037-01-15' } })),
    )
  })

  test('shows nothing for an event with no trip', async () => {
    serve([DENTIST], () => [])
    render(<CalendarView />)
    await openDayView()

    expect(await screen.findByText('Dentiste')).toBeInTheDocument()
    expect(screen.queryByText(/Partir à/)).toBeNull()
  })

  test('a live update moves the time without reloading, a deletion removes the line', async () => {
    let current: unknown[] = [trip('01DENTIST', at(13, 34))]
    serve([DENTIST], () => current)
    render(<CalendarView />)
    await openDayView()
    await screen.findByText('Partir à 13:34 · depuis la maison')
    await waitFor(() => expect(MockEventSource.instances.length).toBeGreaterThan(0))

    current = [trip('01DENTIST', at(14, 34))]
    send({ '@id': '/api/trips/01T01DENTIST', departAt: at(14, 34).toISOString() })
    expect(await screen.findByText('Partir à 14:34 · depuis la maison', {}, { timeout: 5_000 })).toBeInTheDocument()
    expect(screen.queryByText('Partir à 13:34 · depuis la maison')).toBeNull()

    current = []
    send({ '@id': '/api/trips/01T01DENTIST', deleted: true })
    await waitFor(() => expect(screen.queryByText(/Partir à/)).toBeNull(), { timeout: 5_000 })
    expect(screen.getByText('Dentiste')).toBeInTheDocument()
  })

  test('when the trips cannot be read, the day shows without the line and the agenda works', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    serve([DENTIST], () => Promise.reject(new Error('boom')) as Promise<never>)
    render(<CalendarView />)
    await openDayView()

    expect(await screen.findByText('Dentiste')).toBeInTheDocument()
    await waitFor(() => expect(mockGetList.mock.calls.some(([resource]) => resource === 'trips')).toBe(true))
    expect(screen.queryByText(/Partir à/)).toBeNull()
    expect(screen.queryByText(/boom/)).toBeNull()
    error.mockRestore()
  })
})
