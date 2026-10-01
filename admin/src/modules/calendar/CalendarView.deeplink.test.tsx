import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { CalendarView } from './CalendarView'

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

// The deep link the global search sends: `/calendar?eventId=…` or `?mealId=…`
let currentParams = new URLSearchParams()
const mockSetSearchParams = vi.fn()
vi.mock('react-router-dom', () => ({
  useSearchParams: () => [currentParams, mockSetSearchParams],
}))

const mockGetList = vi.fn()
const mockGetOne = vi.fn()
const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    getOne: mockGetOne,
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  }),
  useNotify: () => mockNotify,
}))

const AGENDA = '/api/agendas/01PERSO'

describe('CalendarView deep link from the global search', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    mockGetList.mockImplementation((resource: string) =>
      Promise.resolve({
        data: resource === 'agendas' ? [{ id: AGENDA, name: 'Perso', color: '#3f51b5', default: true }] : [],
        total: 0,
      }),
    )
  })

  test('opens an event from its IRI', async () => {
    currentParams = new URLSearchParams({ eventId: '/api/events/01EVT' })
    mockGetOne.mockResolvedValue({
      data: {
        id: '/api/events/01EVT',
        summary: 'Dentiste',
        startAt: '2026-10-14T09:00:00+00:00',
        endAt: '2026-10-14T10:00:00+00:00',
        allDay: false,
        agenda: AGENDA,
      },
    })

    render(<CalendarView />)

    expect(await screen.findByRole('heading', { name: 'Dentiste' })).toBeInTheDocument()
    expect(mockGetOne).toHaveBeenCalledWith('events', { id: '/api/events/01EVT' })
    expect(mockNotify).not.toHaveBeenCalled()
  })

  test('still resolves a bare identifier (an old link) to the event IRI', async () => {
    currentParams = new URLSearchParams({ eventId: '01EVT' })
    mockGetOne.mockResolvedValue({
      data: {
        id: '/api/events/01EVT',
        summary: 'Dentiste',
        startAt: '2026-10-14T09:00:00+00:00',
        endAt: '2026-10-14T10:00:00+00:00',
        allDay: false,
        agenda: AGENDA,
      },
    })

    render(<CalendarView />)

    await waitFor(() => expect(mockGetOne).toHaveBeenCalledWith('events', { id: '/api/events/01EVT' }))
  })

  test('opens a meal from the meals resource, not as an event', async () => {
    currentParams = new URLSearchParams({ mealId: '/api/meals/01MEAL' })
    mockGetOne.mockResolvedValue({
      data: {
        id: '/api/meals/01MEAL',
        summary: 'Dîner',
        slot: 'dinner',
        startAt: '2026-10-14T19:00:00+00:00',
        recipes: [{ id: '/api/recipes/01R', name: 'Pâtes carbonara' }],
      },
    })

    render(<CalendarView />)

    expect(await screen.findByRole('heading', { name: 'Dîner: Pâtes carbonara' })).toBeInTheDocument()
    expect(mockGetOne).toHaveBeenCalledTimes(1)
    expect(mockGetOne).toHaveBeenCalledWith('meals', { id: '/api/meals/01MEAL' })
    expect(mockNotify).not.toHaveBeenCalled()
  })

  test('says so when the event does not exist', async () => {
    currentParams = new URLSearchParams({ eventId: '/api/events/01GONE' })
    mockGetOne.mockRejectedValue(new Error('Not Found'))

    render(<CalendarView />)

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Événement introuvable', { type: 'warning' }))
  })

  test('clears the parameter so it does not re-trigger', async () => {
    currentParams = new URLSearchParams({ mealId: '/api/meals/01MEAL' })
    mockGetOne.mockResolvedValue({ data: { id: '/api/meals/01MEAL', summary: 'Déjeuner', slot: 'lunch', startAt: '2026-10-14T12:00:00+00:00', recipes: [] } })

    render(<CalendarView />)

    await waitFor(() => expect(mockSetSearchParams).toHaveBeenCalled())
    const update = mockSetSearchParams.mock.calls[0][0] as (prev: URLSearchParams) => URLSearchParams
    expect(update(new URLSearchParams({ mealId: 'x', other: 'y' })).toString()).toBe('other=y')
  })
})
