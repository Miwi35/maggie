import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { CalendarView } from './CalendarView'

/**
 * An answer to an older range never overwrites the range on screen (MAG-284).
 *
 * Every move through the agenda fetches the visible range again, and the answers
 * come back in whatever order the server gives them. The reply to the range the
 * grid *used to* show landing last replaced the tasks of the one it shows now:
 * the chip vanished, and the pencil of the detail card — which looks the task up
 * in that list — silently opened nothing.
 */

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
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  }),
  useNotify: () => vi.fn(),
}))

const AGENDAS = [{ id: '/api/agendas/01PERSO', name: 'Perso', color: '#3f51b5', default: true }]

/** Noon on the 15th, so the task sits inside the month the grid opens on. */
const noon = (() => {
  const date = new Date()
  date.setHours(12, 0, 0, 0)
  date.setDate(15)

  return date
})()

const TASK = {
  id: 'task-1',
  title: 'Sortir les poubelles',
  priority: 'low',
  criticality: 'low',
  dueDate: noon.toISOString(),
  completedAt: new Date(noon.getTime() + 900_000).toISOString(),
}

describe('CalendarView — a late answer for an older range', { timeout: 60_000 }, () => {
  let answerFirstFetch: (tasks: unknown[]) => void = () => {}

  beforeEach(() => {
    vi.clearAllMocks()
    MockEventSource.instances = []
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
    vi.stubGlobal('EventSource', MockEventSource)

    // The first fetch of tasks stays in flight; every later one answers at once.
    let taskCalls = 0
    mockGetList.mockImplementation((resource: string) => {
      if ('agendas' === resource) return Promise.resolve({ data: AGENDAS, total: AGENDAS.length })
      if ('tasks' === resource) {
        taskCalls += 1
        if (1 === taskCalls) {
          return new Promise((resolve) => {
            answerFirstFetch = (tasks) => resolve({ data: tasks, total: tasks.length })
          })
        }

        return Promise.resolve({ data: [TASK], total: 1 })
      }

      return Promise.resolve({ data: [], total: 0 })
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.removeItem('user')
  })

  test('does not take the task off the grid the user is looking at', async () => {
    render(<CalendarView />)

    // A live update refetches the range while the first fetch is still in flight.
    await waitFor(() => expect(MockEventSource.instances.length).toBeGreaterThan(0))
    MockEventSource.instances[0].onmessage?.({ data: '{}' } as MessageEvent)
    expect(await screen.findByText(`✓ ${TASK.title}`)).toBeInTheDocument()

    // The first answer comes last, from before the task was indexed.
    answerFirstFetch([])
    // Not `act`: FullCalendar keeps React busy, and an async `act` never settles around it.
    await new Promise((resolve) => setTimeout(resolve, 200))

    expect(screen.getByText(`✓ ${TASK.title}`)).toBeInTheDocument()
  })
})
