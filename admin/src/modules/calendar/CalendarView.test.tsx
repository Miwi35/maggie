import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { CalendarView } from './CalendarView'

// Mock EventSource
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}
vi.stubGlobal('EventSource', MockEventSource)

// Mock react-admin's useDataProvider
const mockGetList = vi.fn()
const mockDelete = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    delete: mockDelete,
  }),
  useNotify: () => vi.fn(),
}))

describe('CalendarView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    mockGetList.mockResolvedValue({ data: [], total: 0 })
    mockDelete.mockResolvedValue({ data: {} })
  })

  test('renders toolbar with navigation controls', () => {
    render(<CalendarView />)

    expect(screen.getByText("Aujourd'hui")).toBeInTheDocument()
    expect(screen.getByLabelText('Mois précédent(e)')).toBeInTheDocument()
    expect(screen.getByLabelText('Mois suivant(e)')).toBeInTheDocument()
    expect(screen.getByText('Créer')).toBeInTheDocument()
  })

  test('renders view switching buttons', () => {
    render(<CalendarView />)

    expect(screen.getByText('Mois')).toBeInTheDocument()
    expect(screen.getByText('Semaine')).toBeInTheDocument()
    expect(screen.getByText('Jour')).toBeInTheDocument()
  })

  test('fetches agendas and events on mount', async () => {
    render(<CalendarView />)

    await waitFor(() => {
      expect(mockGetList).toHaveBeenCalledWith('agendas', expect.any(Object))
    })
  })
})
