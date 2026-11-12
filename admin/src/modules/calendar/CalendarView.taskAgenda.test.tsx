import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarView } from './CalendarView'

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
  useDataProvider: () => ({ getList: mockGetList, create: vi.fn(), update: vi.fn(), delete: vi.fn() }),
  useNotify: () => vi.fn(),
}))

const AGENDAS = [{ id: '/api/agendas/01JAUNE', name: 'Jaune', color: '#FDD663', default: true }]
const MODULES = [
  { id: '/api/agendas/01REPAS', name: 'Repas', color: '#F6B26B', module: 'cookbook' },
  { id: '/api/agendas/01SANTE', name: 'Santé', color: '#76A5AF', module: 'health' },
  { id: '/api/agendas/01FINANCE', name: 'Finance', color: '#93C47D', module: 'finance' },
  { id: '/api/agendas/01TACHES', name: 'Tâches', color: '#E8A9D9', module: 'tasks' },
]

const noon = new Date()
noon.setHours(12, 0, 0, 0)
noon.setDate(15)
const localDay = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const nextDay = new Date(noon.getTime() + 86_400_000)

const task = (id: string, title: string, extra: Record<string, unknown> = {}) => ({
  id,
  title,
  priority: 'low',
  criticality: 'low',
  scheduledDate: localDay(noon),
  completedAt: null,
  ...extra,
})

describe('CalendarView — module agendas (MAG-820)', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    mockGetList.mockImplementation((resource: string, params: { filter?: Record<string, unknown> }) => {
      if ('agendas' === resource) {
        const modules = params?.filter?.['exists[module]']
        return Promise.resolve(modules ? { data: MODULES, total: MODULES.length } : { data: AGENDAS, total: 1 })
      }
      if ('tasks' === resource) {
        return Promise.resolve({
          data: [
            task('t-sched', 'Tâche planifiée'),
            task('t-due', 'Tâche échue', { scheduledDate: null, dueDate: localDay(nextDay) }),
            task('t-done', 'Tâche faite', { completedAt: noon.toISOString() }),
          ],
          total: 3,
        })
      }

      return Promise.resolve({ data: [], total: 0 })
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('lists every module agenda as a filter line', async () => {
    render(<CalendarView />)

    await waitFor(() =>
      expect(screen.getAllByTestId(/module-agenda-row|maggie-agenda-row/).map((r) => r.textContent)).toEqual(
        expect.arrayContaining(['Repas', 'Santé', 'Finance', 'Tâches']),
      ),
    )
  })

  test('draws only the undone task placed on its scheduled date', async () => {
    render(<CalendarView />)

    await waitFor(() => expect(screen.getAllByText('Tâche planifiée').length).toBeGreaterThan(0))
    expect(screen.queryByText('Tâche échue')).toBeNull()
    expect(screen.queryByText(/Tâche faite/)).toBeNull()
  })

  test('unticking the Tâches agenda hides its tasks', async () => {
    render(<CalendarView />)
    await waitFor(() => expect(screen.getAllByText('Tâche planifiée').length).toBeGreaterThan(0))

    const row = screen.getAllByTestId('module-agenda-row').find((r) => 'Tâches' === r.textContent)!
    await userEvent.click(within(row).getByRole('checkbox'))

    await waitFor(() => expect(screen.queryByText('Tâche planifiée')).toBeNull())
  })
})
