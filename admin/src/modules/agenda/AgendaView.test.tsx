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

  test('renders event list', async () => {
    mockGetList.mockResolvedValue({
      data: [
        { id: '1', summary: 'Team Meeting', startAt: '2026-03-01T10:00:00Z', endAt: '2026-03-01T11:00:00Z', allDay: false, status: 'confirmed' },
        { id: '2', summary: 'Lunch Break', startAt: '2026-03-01T12:00:00Z', endAt: '2026-03-01T13:00:00Z', allDay: false, status: 'confirmed' },
      ],
      total: 2,
    })

    render(<AgendaView />)

    await waitFor(() => {
      expect(screen.getByText('Team Meeting')).toBeInTheDocument()
      expect(screen.getByText('Lunch Break')).toBeInTheDocument()
    })
  })

  test('renders empty state', async () => {
    mockGetList.mockResolvedValue({ data: [], total: 0 })

    render(<AgendaView />)

    await waitFor(() => {
      expect(screen.getByText('No upcoming events.')).toBeInTheDocument()
    })
  })
})
