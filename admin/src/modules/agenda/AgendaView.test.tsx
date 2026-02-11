import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { AgendaView } from './AgendaView'

// Mock EventSource
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}
vi.stubGlobal('EventSource', MockEventSource)

// Mock react-admin's useDataProvider
const mockGetList = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
  }),
}))

describe('AgendaView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
  })

  test('renders FullCalendar with month view', () => {
    mockGetList.mockResolvedValue({ data: [], total: 0 })

    render(<AgendaView />)

    // FullCalendar renders with navigation buttons and a month title
    expect(screen.getByTitle('Previous month')).toBeInTheDocument()
    expect(screen.getByTitle('Next month')).toBeInTheDocument()
  })

  test('fetches and displays events', async () => {
    mockGetList.mockResolvedValue({
      data: [
        { id: '1', summary: 'Team Meeting', startAt: '2026-02-15T10:00:00Z', endAt: '2026-02-15T11:00:00Z', allDay: false, status: 'confirmed' },
      ],
      total: 1,
    })

    render(<AgendaView />)

    await waitFor(() => {
      expect(screen.getByText('Team Meeting')).toBeInTheDocument()
    })
  })
})
