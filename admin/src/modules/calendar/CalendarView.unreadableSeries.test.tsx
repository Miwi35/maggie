import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { CalendarView } from './CalendarView'

/**
 * A recurring timed event whose end is missing or unreadable must not take the
 * calendar down (MAG-660). Its duration was NaN, and `new Date(NaN).toISOString()`
 * throws `RangeError: Invalid time value` inside the render.
 */

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
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

const AGENDA = '/api/agendas/ag1'

const noon = (() => {
  const date = new Date()
  date.setHours(12, 0, 0, 0)
  date.setDate(15)

  return date
})()

describe('CalendarView — a series with no readable end', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
    vi.stubGlobal('EventSource', MockEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.removeItem('user')
  })

  test.each([
    ['missing', null],
    ['not a date', 'not-a-date'],
  ])('still draws the calendar when the end of a weekly event is %s', async (_label, endAt) => {
    mockGetList.mockImplementation((resource: string) => {
      if ('agendas' === resource) {
        return Promise.resolve({ data: [{ id: AGENDA, name: 'Perso', color: '#1976d2', default: true }], total: 1 })
      }
      if ('events' === resource) {
        const events = [
          { id: '/api/events/ev1', summary: 'Sport', startAt: noon.toISOString(), endAt, allDay: false, agenda: AGENDA, rrule: 'FREQ=WEEKLY' },
        ]

        return Promise.resolve({ data: events, total: events.length })
      }

      return Promise.resolve({ data: [], total: 0 })
    })

    render(<CalendarView />)

    await waitFor(() => expect(screen.getAllByText('Sport').length).toBeGreaterThan(0))
    expect(screen.getByText("Aujourd'hui")).toBeInTheDocument()
  })
})
