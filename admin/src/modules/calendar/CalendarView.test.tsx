import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
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
const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    delete: mockDelete,
    update: mockUpdate,
    create: mockCreate,
  }),
  useNotify: () => mockNotify,
}))

// react-admin's Hydra provider puts the IRI in `record.id`, never a bare identifier
const AGENDA = '/api/agendas/ag1'

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

  // The sidebar's rows, the toolbar and the dialogs are addressed by role and name in
  // `CalendarView.handles.test.tsx`, which owns that contract with the agenda
  // journeys. This file owns behaviour; the two do not overlap.

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
            data: [{ id: AGENDA, name: 'Perso', color: '#1976d2', isDefault: true }],
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
      serveEvents([{ id: '/api/events/ev1', summary: 'Dentiste', startAt, endAt, allDay: false, agenda: AGENDA }])
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
            id: '/api/events/ev1',
            data: expect.objectContaining({ summary: 'Dentiste (contrôle)', startAt, endAt, allDay: false }),
          }),
        ),
      )
    })

    test('asks which occurrences to change before editing a recurring series', async () => {
      serveEvents([
        { id: '/api/events/ev2', summary: 'Sport', startAt, endAt, allDay: false, agenda: AGENDA, rrule: 'FREQ=WEEKLY' },
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
        { id: '/api/events/ev3', summary: 'Sport', startAt, endAt, allDay: false, agenda: AGENDA, rrule: 'FREQ=WEEKLY' },
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
            id: '/api/events/ev3',
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
            id: '/api/events/ev3',
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
  // Every write below goes out with what the API accepts: the master's IRI where an
  // IRI is expected, the bare identifier where a controller `find()`s a ULID column.
  describe('with the IRIs react-admin hands over', { timeout: 30_000 }, () => {
    const noon = new Date()
    noon.setHours(12, 0, 0, 0)
    noon.setDate(15)
    const startAt = noon.toISOString()
    const endAt = new Date(noon.getTime() + 3600_000).toISOString()
    const MASTER = '/api/events/01SPORT'
    const series = {
      id: MASTER,
      summary: 'Sport',
      startAt,
      endAt,
      allDay: false,
      agenda: AGENDA,
      rrule: 'FREQ=WEEKLY',
    }

    const serve = (events: unknown[], agendas: unknown[] = [{ id: AGENDA, name: 'Perso', isDefault: true }]) => {
      mockGetList.mockImplementation((resource: string) => {
        if (resource === 'agendas') return Promise.resolve({ data: agendas, total: agendas.length })
        if (resource === 'events') return Promise.resolve({ data: events, total: events.length })
        return Promise.resolve({ data: [], total: 0 })
      })
    }

    const answerOccurrence = async (action: 'Modifier' | 'Supprimer') => {
      const chips = await screen.findAllByText('Sport')
      await userEvent.click(chips[0])
      await userEvent.click(await screen.findByRole('button', { name: action }))
      if (action === 'Modifier') {
        const edit = await screen.findByRole('dialog')
        fireEvent.change(within(edit).getByLabelText(/Résumé/), { target: { value: 'Sport (piscine)' } })
        await userEvent.click(within(edit).getByRole('button', { name: 'Enregistrer' }))
      }
      const confirm = await screen.findByRole('dialog', { name: /l'événement récurrent$/ })
      await userEvent.click(within(confirm).getByLabelText('Cet événement'))
      await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))
    }

    test('deleting one occurrence cancels it against the master IRI, not a doubled one', async () => {
      serve([series])
      render(<CalendarView />)

      await answerOccurrence('Supprimer')

      await waitFor(() =>
        expect(mockCreate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({
            data: expect.objectContaining({ recurringEvent: MASTER, status: 'cancelled', agenda: AGENDA }),
          }),
        ),
      )
      expect(mockNotify).toHaveBeenCalledWith('Occurrence supprimée', { type: 'success' })
    })

    test('a refused write shows the error, announces no success and keeps the dialog open', async () => {
      serve([series])
      mockCreate.mockRejectedValue(new Error('Invalid IRI'))
      render(<CalendarView />)

      await answerOccurrence('Modifier')

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Invalid IRI', { type: 'error' }))
      expect(mockNotify).not.toHaveBeenCalledWith('Occurrence modifiée', { type: 'success' })
      // longer than MUI's exit transition, so a dialog that is closing has left the DOM
      await new Promise((resolve) => setTimeout(resolve, 500))
      expect(screen.getByRole('dialog', { name: "Modifier l'événement récurrent" })).toBeInTheDocument()
    })

    test('an exception is matched to its master and replaces the occurrence it moved', async () => {
      serve([
        series,
        {
          id: '/api/events/01EXC',
          summary: 'Sport déplacé',
          startAt: new Date(noon.getTime() + 2 * 3600_000).toISOString(),
          endAt: new Date(noon.getTime() + 3 * 3600_000).toISOString(),
          allDay: false,
          agenda: AGENDA,
          recurringEvent: MASTER,
          originalStartAt: startAt,
          status: 'confirmed',
        },
      ])
      render(<CalendarView />)

      expect(await screen.findByText('Sport déplacé')).toBeInTheDocument()
      const pad = (n: number) => String(n).padStart(2, '0')
      const day = `${noon.getFullYear()}-${pad(noon.getMonth() + 1)}-${pad(noon.getDate())}`
      const cell = document.querySelector(`td[data-date="${day}"]`) as HTMLElement
      expect(within(cell).queryByText('Sport')).toBeNull()
    })

    describe('the agenda options menu', () => {
      const FAMILLE = '/api/agendas/01FAMILLE'
      const fetchMock = vi.fn()

      beforeEach(() => {
        fetchMock.mockReset()
        fetchMock.mockResolvedValue({ ok: true, status: 204, json: () => Promise.resolve({}) })
        vi.stubGlobal('fetch', fetchMock)
        serve([], [{ id: FAMILLE, name: 'Famille', isDefault: false }])
      })

      afterEach(() => {
        vi.unstubAllGlobals()
        vi.stubGlobal('EventSource', MockEventSource)
      })

      const openMenu = async (item: string) => {
        render(<CalendarView />)
        await userEvent.click(await screen.findByRole('button', { name: "Options de l'agenda Famille" }))
        await userEvent.click(await screen.findByRole('menuitem', { name: item }))
      }

      test('exports the agenda by its bare identifier', async () => {
        await openMenu('Exporter vers Google')

        await waitFor(() => expect(fetchMock).toHaveBeenCalled())
        const [url, init] = fetchMock.mock.calls[0]
        expect(String(url)).toMatch(/\/calendar\/google\/export$/)
        expect(JSON.parse(init.body)).toEqual({ agendaId: '01FAMILLE' })
      })

      test('deletes the agenda at its own path, not a doubled one', async () => {
        await openMenu('Supprimer')

        const dialog = await screen.findByRole('dialog', { name: "Supprimer l'agenda" })
        await userEvent.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

        await waitFor(() => expect(fetchMock).toHaveBeenCalled())
        const [url, init] = fetchMock.mock.calls[0]
        expect(String(url)).toBe('http://localhost/api/agendas/01FAMILLE')
        expect(init.method).toBe('DELETE')
      })
    })
  })
})
