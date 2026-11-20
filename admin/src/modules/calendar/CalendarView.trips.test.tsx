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

const lineOf = (title: string) => screen.getByLabelText(`Trajet vers ${title}`)

/** The trips of the day list (MAG-659, MAG-858). */
describe('CalendarView — trips in the day list', { timeout: 60_000 }, () => {
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

  test('draws the trip of a 5-minute event as its own line, before the event, at its departure', async () => {
    const short = { ...event('01SHORT', 'Passage éclair', at(18, 25)), endAt: at(18, 30).toISOString() }
    serve([short], () => [trip('01SHORT', at(17, 39))])
    render(<CalendarView />)
    await openDayView()

    const line = await screen.findByLabelText('Trajet vers Passage éclair')
    expect(line.closest('.fc-event')).toBeNull()
    expect(line).toHaveTextContent('Partir à 17:39')
    const row = screen.getByText('Passage éclair').closest('.day-row') as HTMLElement
    expect(line.compareDocumentPosition(row) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  test('opening Jour with a trip of the day stays on Jour and reads the day once', async () => {
    serve([DENTIST], () => [trip('01DENTIST', at(13, 34))])
    render(<CalendarView />)
    await screen.findByText('Dentiste')
    await openDayView()
    await screen.findByLabelText('Trajet vers Dentiste')
    await new Promise((resolve) => setTimeout(resolve, 2_000))

    expect(screen.getByRole('button', { name: 'Jour' })).toHaveClass('MuiButton-contained')
    expect(mockGetList.mock.calls.filter(([resource]) => resource === 'trips')).toHaveLength(1)
    const monthReads = mockGetList.mock.calls.filter(
      ([resource, params]) =>
        resource === 'events' && JSON.stringify(params).includes('2037-01-31'),
    )
    expect(monthReads.length).toBeLessThanOrEqual(1)
  })

  test('shows when to leave and the steps on a line of its own, and nothing for the others', async () => {
    serve([DENTIST, SPORT], () => [trip('01DENTIST', at(13, 34))])
    render(<CalendarView />)
    await openDayView()

    const line = await screen.findByLabelText('Trajet vers Dentiste')
    expect(line).toHaveTextContent('Partir à 13:34')
    expect(line).toHaveTextContent('depuis la maison')
    expect(line).toHaveTextContent('4 min')
    expect(line).toHaveTextContent('14 min')
    expect(screen.queryByLabelText('Trajet vers Sport')).toBeNull()
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

  test('the title of the day is capitalised, without the year', async () => {
    serve([DENTIST], () => [])
    render(<CalendarView />)
    await openDayView()

    expect(await screen.findByText('Jeudi 15 janvier')).toBeInTheDocument()
  })

  test('the return of a whole-day stay shows in the list, Retour before the hours', async () => {
    const stay = {
      ...event('01STAY', 'Gîte du Pouldu', at(0)),
      allDay: true,
      startDate: '2037-01-15',
      endDate: '2037-01-16',
      startAt: null,
      endAt: null,
    }
    serve([stay], () => [trip('01STAY', at(11), { leg: 'return', arriveAt: at(11, 14).toISOString(), steps: [] })])
    render(<CalendarView />)
    await openDayView()

    expect(await screen.findByText('Retour · Partir à 11:00 · arrivée 11:14')).toBeInTheDocument()
    expect(screen.getByText('Journée')).toBeInTheDocument()
  })

  test('shows nothing for an event with no trip', async () => {
    serve([DENTIST], () => [])
    render(<CalendarView />)
    await openDayView()

    expect(await screen.findByText('Dentiste')).toBeInTheDocument()
    expect(screen.queryByText(/Partir à/)).toBeNull()
  })

  test('a Mercure update moves the line, a deleted trip leaves the list', async () => {
    let current: unknown[] = [trip('01DENTIST', at(13, 34)), trip('01SPORT', at(17, 30))]
    serve([DENTIST, SPORT], () => current)
    render(<CalendarView />)
    await openDayView()
    await screen.findByText(/Partir à 13:34/)
    await waitFor(() => expect(MockEventSource.instances.length).toBeGreaterThan(0))

    current = [trip('01DENTIST', at(14, 34)), trip('01SPORT', at(17, 30))]
    send({ '@id': '/api/trips/01T01DENTIST', departAt: at(14, 34).toISOString() })
    expect(await screen.findByText(/Partir à 14:34/, {}, { timeout: 5_000 })).toBeInTheDocument()
    expect(screen.queryByText(/Partir à 13:34/)).toBeNull()

    current = [trip('01SPORT', at(17, 30))]
    send({ '@id': '/api/trips/01T01DENTIST', deleted: true })
    await waitFor(() => expect(screen.queryByLabelText('Trajet vers Dentiste')).toBeNull(), { timeout: 5_000 })
    expect(screen.getByText('Dentiste')).toBeInTheDocument()
    expect(lineOf('Sport')).toBeInTheDocument()
  })

  test('a failed trips read leaves the events, without the line', async () => {
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
