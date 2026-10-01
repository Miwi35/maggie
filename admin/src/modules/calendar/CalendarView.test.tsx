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

  /**
   * The sidebar's agenda rows are addressable.
   *
   * Asserted here because the e2e agenda journeys (MAG-100) reach them by
   * `agenda-row` and by the ⋮ button's accessible name, and both are the kind of
   * handle a refactor drops without any test noticing — the journeys would then
   * fail far from the change, on a stack that takes minutes to start.
   */
  test('each agenda is a named row with its own options button', async () => {
    mockGetList.mockImplementation((resource: string) =>
      Promise.resolve(
        'agendas' === resource
          ? { data: [{ id: 'ag1', name: 'Perso', color: '#1976d2', isDefault: true }], total: 1 }
          : { data: [], total: 0 },
      ),
    )

    render(<CalendarView />)

    const row = await screen.findByTestId('agenda-row')
    expect(within(row).getByText('Perso')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: "Options de l'agenda Perso" })).toBeInTheDocument()
  })

  // FullCalendar re-renders the whole grid on each interaction: slow on a busy CI runner
  describe('editing an event with the pencil', { timeout: 30_000 }, () => {
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
      fireEvent.change(summary, { target: { value: 'Dentiste (contrôle)' } })
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
      fireEvent.change(summary, { target: { value: 'Sport (piscine)' } })
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

    // Edits the second occurrence of a weekly series (a week after the master): moved two
    // hours later, renamed, located. Returns what the user edited, read from the form.
    const editSecondOccurrence = async (scope: string) => {
      serveEvents([
        { id: 'ev3', summary: 'Sport', startAt, endAt, allDay: false, agenda: 'ag1', rrule: 'FREQ=WEEKLY' },
      ])
      render(<CalendarView />)

      const occurrences = await screen.findAllByText('Sport')
      await userEvent.click(occurrences[1])
      await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))

      const edit = await screen.findByRole('dialog')
      const occurrenceStart = new Date((within(edit).getByLabelText(/Début/) as HTMLInputElement).value)
      const newStart = new Date(occurrenceStart.getTime() + 2 * 3600_000)
      const newEnd = new Date(occurrenceStart.getTime() + 3 * 3600_000)

      const summary = within(edit).getByLabelText(/Résumé/)
      fireEvent.change(summary, { target: { value: 'Sport (piscine)' } })
      fireEvent.change(within(edit).getByLabelText(/Début/), { target: { value: localInput(newStart) } })
      fireEvent.change(within(edit).getByLabelText(/Fin/), { target: { value: localInput(newEnd) } })
      fireEvent.change(within(edit).getByLabelText('Lieu'), { target: { value: 'Piscine' } })
      await userEvent.click(within(edit).getByRole('button', { name: 'Enregistrer' }))

      const confirm = await screen.findByRole('dialog')
      await userEvent.click(within(confirm).getByLabelText(scope))
      await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))

      return { occurrenceStart, newStart, newEnd }
    }

    test('"all occurrences" shifts the master by the edited delta, not onto the edited date', async () => {
      const { occurrenceStart, newStart, newEnd } = await editSecondOccurrence('Tous les événements')

      // Only the 2h delta applies to the master (the 15th at noon); the week gap does not
      const delta = newStart.getTime() - occurrenceStart.getTime()
      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({
            id: 'ev3',
            data: expect.objectContaining({
              summary: 'Sport (piscine)',
              location: 'Piscine',
              startAt: new Date(noon.getTime() + delta).toISOString(),
              endAt: new Date(noon.getTime() + delta + (newEnd.getTime() - newStart.getTime())).toISOString(),
            }),
          }),
        ),
      )
      expect(mockCreate).not.toHaveBeenCalled()
    })

    test('"this and following" ends the series the day before and starts a new one', async () => {
      const { occurrenceStart, newStart } = await editSecondOccurrence('Cet événement et tous les suivants')

      const dayBefore = new Date(occurrenceStart)
      dayBefore.setUTCDate(dayBefore.getUTCDate() - 1)
      const until = dayBefore.toISOString().slice(0, 10).replace(/-/g, '')
      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({
            id: 'ev3',
            data: { rrule: `FREQ=WEEKLY;UNTIL=${until}T235959Z` },
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
              startAt: newStart.toISOString(),
            }),
          }),
        ),
      )
    })
  })
})
