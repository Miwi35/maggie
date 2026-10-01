import { describe, test, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarView } from './CalendarView'

// Mock EventSource
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}
vi.stubGlobal('EventSource', MockEventSource)

// Mock react-router-dom
vi.mock('react-router-dom', () => ({
  useSearchParams: () => [new URLSearchParams(), vi.fn()],
}))

// Mock react-admin's useDataProvider
const mockGetList = vi.fn()
const mockDelete = vi.fn()
const mockUpdate = vi.fn()
const mockCreate = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    delete: mockDelete,
    update: mockUpdate,
    create: mockCreate,
  }),
  useNotify: () => vi.fn(),
}))

describe('CalendarView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    mockGetList.mockResolvedValue({ data: [], total: 0 })
    mockDelete.mockResolvedValue({ data: {} })
    mockUpdate.mockResolvedValue({ data: {} })
    mockCreate.mockResolvedValue({ data: {} })
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

  describe('editing an event with the pencil', () => {
    const noon = new Date()
    noon.setHours(12, 0, 0, 0)
    // Start on the 15th so the event is always inside the visible month
    noon.setDate(15)
    const startAt = noon.toISOString()
    const endAt = new Date(noon.getTime() + 3600_000).toISOString()

    const serveEvents = (events: unknown[]) => {
      mockGetList.mockImplementation((resource: string) => {
        if (resource === 'agendas') {
          return Promise.resolve({
            data: [{ id: 'ag1', name: 'Perso', color: '#1976d2', isDefault: true }],
            total: 1,
          })
        }
        if (resource === 'events') return Promise.resolve({ data: events, total: events.length })
        return Promise.resolve({ data: [], total: 0 })
      })
    }

    const openPencil = async (title: string) => {
      await userEvent.click(await screen.findByText(title))
      await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))
    }

    test('opens the edit dialog and saves a one-off event', async () => {
      serveEvents([{ id: 'ev1', summary: 'Dentiste', startAt, endAt, allDay: false, agenda: 'ag1' }])
      render(<CalendarView />)

      await openPencil('Dentiste')

      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText("Modifier l'événement")).toBeInTheDocument()
      const summary = within(dialog).getByLabelText(/Résumé/)
      await userEvent.clear(summary)
      await userEvent.type(summary, 'Dentiste (contrôle)')
      await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({
            id: 'ev1',
            data: expect.objectContaining({ summary: 'Dentiste (contrôle)', startAt, endAt, allDay: false }),
          }),
        ),
      )
    })

    test('asks which occurrences to change before editing a recurring series', async () => {
      serveEvents([
        { id: 'ev2', summary: 'Sport', startAt, endAt, allDay: false, agenda: 'ag1', rrule: 'FREQ=WEEKLY' },
      ])
      render(<CalendarView />)

      const occurrences = await screen.findAllByText('Sport')
      await userEvent.click(occurrences[0])
      await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))

      const edit = await screen.findByRole('dialog')
      const summary = within(edit).getByLabelText(/Résumé/)
      await userEvent.clear(summary)
      await userEvent.type(summary, 'Sport (piscine)')
      await userEvent.click(within(edit).getByRole('button', { name: 'Enregistrer' }))

      const confirm = await screen.findByRole('dialog')
      expect(within(confirm).getByText("Modifier l'événement récurrent")).toBeInTheDocument()
      expect(mockUpdate).not.toHaveBeenCalled()
      expect(mockCreate).not.toHaveBeenCalled()

      await userEvent.click(within(confirm).getByLabelText('Cet événement'))
      await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))

      await waitFor(() =>
        expect(mockCreate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({
            data: expect.objectContaining({
              summary: 'Sport (piscine)',
              recurringEvent: '/api/events/ev2',
              status: 'confirmed',
            }),
          }),
        ),
      )
      expect(mockUpdate).not.toHaveBeenCalled()
    })

    const localInput = (d: Date) => {
      const pad = (n: number) => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
    }

    // Edits the first occurrence of a weekly series: moved two hours later, renamed, located
    const editFirstOccurrence = async (scope: string) => {
      serveEvents([
        { id: 'ev3', summary: 'Sport', startAt, endAt, allDay: false, agenda: 'ag1', rrule: 'FREQ=WEEKLY' },
      ])
      render(<CalendarView />)

      const occurrences = await screen.findAllByText('Sport')
      await userEvent.click(occurrences[0])
      await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))

      const edit = await screen.findByRole('dialog')
      const summary = within(edit).getByLabelText(/Résumé/)
      await userEvent.clear(summary)
      await userEvent.type(summary, 'Sport (piscine)')
      fireEvent.change(within(edit).getByLabelText(/Début/), {
        target: { value: localInput(new Date(noon.getTime() + 2 * 3600_000)) },
      })
      fireEvent.change(within(edit).getByLabelText(/Fin/), {
        target: { value: localInput(new Date(noon.getTime() + 3 * 3600_000)) },
      })
      await userEvent.type(within(edit).getByLabelText('Lieu'), 'Piscine')
      await userEvent.click(within(edit).getByRole('button', { name: 'Enregistrer' }))

      const confirm = await screen.findByRole('dialog')
      await userEvent.click(within(confirm).getByLabelText(scope))
      await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))
    }

    test('"all occurrences" shifts the master by the edited delta and sends the new fields', async () => {
      await editFirstOccurrence('Tous les événements')

      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({
            id: 'ev3',
            data: expect.objectContaining({
              summary: 'Sport (piscine)',
              location: 'Piscine',
              startAt: new Date(noon.getTime() + 2 * 3600_000).toISOString(),
              endAt: new Date(noon.getTime() + 3 * 3600_000).toISOString(),
            }),
          }),
        ),
      )
      expect(mockCreate).not.toHaveBeenCalled()
    })

    test('"this and following" ends the series and starts a new one with the new fields', async () => {
      await editFirstOccurrence('Cet événement et tous les suivants')

      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({
            id: 'ev3',
            data: { rrule: expect.stringContaining('UNTIL=') },
          }),
        ),
      )
      await waitFor(() =>
        expect(mockCreate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({
            data: expect.objectContaining({
              summary: 'Sport (piscine)',
              location: 'Piscine',
              rrule: 'FREQ=WEEKLY',
              startAt: new Date(noon.getTime() + 2 * 3600_000).toISOString(),
            }),
          }),
        ),
      )
    })
  })
})
