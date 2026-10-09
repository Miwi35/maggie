import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { CalendarView } from './CalendarView'

/**
 * A module's agenda is internal (MAG-354): the general calendar lists « Repas » once,
 * as the module's line, and the module has a view of its own that shows nothing else.
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

const today = new Date()
const isoDay = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-15`

const ANSWERS: Record<string, unknown[]> = {
  agendas: [
    { id: '/api/agendas/01PERSO', name: 'Perso', color: '#3f51b5', default: true },
    // What an older API still returns: the module's agenda, in the list.
    { id: '/api/agendas/01REPAS', name: 'Repas', color: '#FF6B35', default: false, module: 'cookbook' },
  ],
  events: [
    {
      id: '/api/events/01RDV',
      summary: 'Dentiste',
      startAt: `${isoDay}T10:00:00+00:00`,
      endAt: `${isoDay}T11:00:00+00:00`,
      allDay: false,
      agenda: '/api/agendas/01PERSO',
    },
  ],
  meals: [
    { id: '/api/meals/01MEAL', date: isoDay, slot: 'lunch', summary: 'Déjeuner', recipes: [{ id: 'r1', name: 'Pâtes' }] },
  ],
  tasks: [],
}

describe('CalendarView — module agendas', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: () => Promise.resolve({}) }))
    mockGetList.mockImplementation((resource: string) =>
      Promise.resolve({ data: ANSWERS[resource] ?? [], total: (ANSWERS[resource] ?? []).length }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('the general calendar lists « Repas » once, as the module line', async () => {
    render(<CalendarView />)

    await waitFor(() => expect(screen.getAllByTestId('agenda-row')).toHaveLength(1))
    expect(screen.getByTestId('agenda-row')).toHaveTextContent('Perso')
    expect(screen.getAllByText('Repas')).toHaveLength(1)
  })

  test('the module view shows its meals and nothing else', async () => {
    render(<CalendarView moduleKey="cookbook" />)

    await screen.findByText(/Déj: Pâtes/)
    expect(screen.queryByText('Dentiste')).not.toBeInTheDocument()
    expect(screen.getByTestId('module-calendar-title')).toHaveTextContent('Repas')
    expect(screen.queryByTestId('agenda-row')).not.toBeInTheDocument()
    expect(screen.queryByText('Tâches')).not.toBeInTheDocument()
    expect(screen.queryByText('Créer')).not.toBeInTheDocument()
    expect(mockGetList).not.toHaveBeenCalledWith('events', expect.anything())
    expect(mockGetList).not.toHaveBeenCalledWith('agendas', expect.anything())
    expect(mockGetList).toHaveBeenCalledWith('meals', expect.anything())
  })

  test('the module view still switches between week and month', async () => {
    render(<CalendarView moduleKey="cookbook" />)

    expect(screen.getByText('Semaine')).toBeInTheDocument()
    expect(screen.getByText('Mois')).toBeInTheDocument()
  })
})
