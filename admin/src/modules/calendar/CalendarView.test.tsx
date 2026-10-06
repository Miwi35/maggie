import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarView } from './CalendarView'

// Mock EventSource
class MockEventSource {
  static instances: MockEventSource[] = []
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    MockEventSource.instances.push(this)
  }
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
    MockEventSource.instances = []
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

  test('also asks for events that started before the range and end inside it', async () => {
    render(<CalendarView />)

    await waitFor(() => {
      expect(mockGetList).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({
          filter: expect.objectContaining({
            'endAt[after]': expect.any(String),
            'startAt[strictly_before]': expect.any(String),
          }),
        }),
      )
    })
  })

  test('refetches again after a live update, once the worker has indexed the row', { timeout: 15_000 }, async () => {
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
    try {
      render(<CalendarView />)
      const eventCalls = () => mockGetList.mock.calls.filter(([resource]) => resource === 'events').length

      await waitFor(() => expect(MockEventSource.instances.length).toBeGreaterThan(0))
      await waitFor(() => expect(eventCalls()).toBeGreaterThan(0))
      const before = eventCalls()

      MockEventSource.instances[0].onmessage?.({ data: '{}' } as MessageEvent)

      // Three event queries per refetch: the range, the series, the multi-day events.
      await waitFor(() => expect(eventCalls()).toBeGreaterThanOrEqual(before + 3))
      await waitFor(() => expect(eventCalls()).toBeGreaterThanOrEqual(before + 6), { timeout: 5_000 })
    } finally {
      localStorage.removeItem('user')
    }
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
            data: [{ id: AGENDA, name: 'Perso', color: '#1976d2', default: true }],
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

    /**
     * The exception split off a series keeps the series' reminders (MAG-121).
     *
     * It is a brand-new row, so anything the form holds that the create payload
     * does not name is gone — and a reminder lost that way is invisible until the
     * day nobody is reminded.
     */
    test('an occurrence split off a series keeps its reminders', async () => {
      const reminders = { useDefault: false, overrides: [{ method: 'popup', minutes: 30 }] }
      serveEvents([
        { id: '/api/events/ev2', summary: 'Sport', startAt, endAt, allDay: false, agenda: AGENDA, rrule: 'FREQ=WEEKLY', reminders },
      ])
      render(<CalendarView />)

      const occurrences = await screen.findAllByText('Sport')
      await userEvent.click(occurrences[0])
      await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))

      const edit = await screen.findByRole('dialog')
      // The form opened on the series' reminder, untouched.
      expect(within(edit).getByRole('combobox', { name: 'Rappel' })).toHaveTextContent('30 minutes avant')
      await userEvent.click(within(edit).getByRole('button', { name: 'Enregistrer' }))

      const confirm = await screen.findByRole('dialog')
      await userEvent.click(within(confirm).getByLabelText('Cet événement'))
      await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))

      await waitFor(() =>
        expect(mockCreate).toHaveBeenCalledWith(
          'events',
          expect.objectContaining({ data: expect.objectContaining({ reminders }) }),
        ),
      )
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

    const serve = (events: unknown[], agendas: unknown[] = [{ id: AGENDA, name: 'Perso', default: true }]) => {
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

    describe('deleting a meal from its card', () => {
      const MEAL = '/api/meals/01DINNER'
      const day = `${noon.getFullYear()}-${String(noon.getMonth() + 1).padStart(2, '0')}-15`
      const meal = { id: MEAL, date: day, slot: 'dinner', summary: 'Dîner', recipes: [{ id: '/api/recipes/01R', name: 'Pâtes' }] }

      const serveMeal = () => {
        mockGetList.mockImplementation((resource: string) => {
          if (resource === 'agendas') return Promise.resolve({ data: [{ id: AGENDA, name: 'Perso', default: true }], total: 1 })
          if (resource === 'meals') return Promise.resolve({ data: [meal], total: 1 })
          return Promise.resolve({ data: [], total: 0 })
        })
      }

      const clickDelete = async () => {
        const chips = await screen.findAllByText('Dîner: Pâtes')
        await userEvent.click(chips[0])
        await userEvent.click(await screen.findByRole('button', { name: 'Supprimer' }))
      }

      test('deletes the meal by its IRI on the meals resource, not an event', async () => {
        serveMeal()
        render(<CalendarView />)

        await clickDelete()

        await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('meals', { id: MEAL, previousData: { id: MEAL } }))
        expect(mockDelete).not.toHaveBeenCalledWith('events', expect.anything())
        expect(mockNotify).toHaveBeenCalledWith('Repas supprimé', { type: 'success' })
      })

      test('says so when the meal cannot be deleted', async () => {
        serveMeal()
        mockDelete.mockRejectedValue(new Error('Forbidden'))
        render(<CalendarView />)

        await clickDelete()

        await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Forbidden', { type: 'error' }))
        expect(mockNotify).not.toHaveBeenCalledWith('Repas supprimé', { type: 'success' })
      })
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
        serve([], [{ id: FAMILLE, name: 'Famille', default: false }])
      })

      afterEach(() => {
        vi.unstubAllGlobals()
        vi.stubGlobal('EventSource', MockEventSource)
      })

      // The saved preferences are read on mount too; they are not what these tests are about.
      const agendaCalls = () => fetchMock.mock.calls.filter(([url]) => !String(url).includes('/user_preferences/'))

      const openMenu = async (item: string) => {
        render(<CalendarView />)
        await userEvent.click(await screen.findByRole('button', { name: "Options de l'agenda Famille" }))
        await userEvent.click(await screen.findByRole('menuitem', { name: item }))
      }

      test('exports the agenda by its bare identifier', async () => {
        await openMenu('Exporter vers Google')

        await waitFor(() => expect(agendaCalls()).not.toHaveLength(0))
        const [url, init] = agendaCalls()[0]
        expect(String(url)).toMatch(/\/calendar\/google\/export$/)
        expect(JSON.parse(init.body)).toEqual({ agendaId: '01FAMILLE' })
      })

      test('deletes the agenda at its own path, not a doubled one', async () => {
        await openMenu('Supprimer')

        const dialog = await screen.findByRole('dialog', { name: "Supprimer l'agenda" })
        await userEvent.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

        await waitFor(() => expect(agendaCalls()).not.toHaveLength(0))
        const [url, init] = agendaCalls()[0]
        expect(String(url)).toBe('http://localhost/api/agendas/01FAMILLE')
        expect(init.method).toBe('DELETE')
      })
    })
  })

  // MAG-149: the default agenda is where Maggie files an appointment when
  // nothing else says where, and only the owner can pick it.
  describe('the default agenda', { timeout: 30_000 }, () => {
    const PERSO = '/api/agendas/01PERSO'
    const CONCERTS = '/api/agendas/01CONCERTS'

    const serveAgendas = (agendas: unknown[]) => {
      mockGetList.mockImplementation((resource: string) => {
        if (resource === 'agendas') return Promise.resolve({ data: agendas, total: agendas.length })
        return Promise.resolve({ data: [], total: 0 })
      })
    }

    const agendaCalls = () => mockGetList.mock.calls.filter(([resource]) => resource === 'agendas').length

    const chooseConcerts = async () => {
      render(<CalendarView />)
      await userEvent.click(await screen.findByRole('button', { name: "Options de l'agenda Concerts" }))
      await userEvent.click(await screen.findByRole('menuitem', { name: 'Définir comme agenda par défaut' }))
    }

    beforeEach(() => {
      serveAgendas([
        { id: CONCERTS, name: 'Concerts', default: false },
        { id: PERSO, name: 'Perso', default: true },
      ])
    })

    test('badges the default agenda, and only that one', async () => {
      render(<CalendarView />)

      const rows = await screen.findAllByTestId('agenda-row')
      const perso = rows.find((row) => row.textContent?.includes('Perso'))
      const concerts = rows.find((row) => row.textContent?.includes('Concerts'))

      expect(within(perso!).getByTestId('agenda-default-badge')).toBeInTheDocument()
      expect(within(perso!).getByTitle('Agenda par défaut')).toBeInTheDocument()
      expect(within(concerts!).queryByTestId('agenda-default-badge')).not.toBeInTheDocument()
    })

    test('marks the chosen agenda as the default and reloads the list', async () => {
      await waitFor(() => undefined)
      const before = agendaCalls()

      await chooseConcerts()

      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith('agendas', {
          id: CONCERTS,
          data: { isDefault: true },
          previousData: { id: CONCERTS },
        }),
      )
      await waitFor(() => expect(agendaCalls()).toBeGreaterThan(before))
      expect(mockNotify).toHaveBeenCalledWith("« Concerts » est maintenant l'agenda par défaut", { type: 'success' })
    })

    test('says so when the choice is refused', async () => {
      mockUpdate.mockRejectedValue(new Error('Forbidden'))

      await chooseConcerts()

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Forbidden', { type: 'error' }))
    })

    test('does not offer to make the default agenda the default again', async () => {
      render(<CalendarView />)
      await userEvent.click(await screen.findByRole('button', { name: "Options de l'agenda Perso" }))

      expect(await screen.findByRole('menuitem', { name: 'Supprimer' })).toBeInTheDocument()
      expect(screen.queryByRole('menuitem', { name: 'Définir comme agenda par défaut' })).not.toBeInTheDocument()
    })

    test('reloads the agendas when the default changes elsewhere', async () => {
      localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
      try {
        render(<CalendarView />)
        await waitFor(() => expect(MockEventSource.instances.some((es) => es.url.includes('agendas'))).toBe(true))
        await screen.findAllByTestId('agenda-row')
        const before = agendaCalls()

        MockEventSource.instances.find((es) => es.url.includes('agendas'))!.onmessage?.({ data: '{}' } as MessageEvent)

        await waitFor(() => expect(agendaCalls()).toBeGreaterThan(before))
      } finally {
        localStorage.removeItem('user')
      }
    })
  })

  // MAG-148: the owner connected the same Google calendar twice. The guard
  // against it lives here, and it reads `googleCalendarId` off the agenda — a
  // field the collection, served from Elasticsearch, did not carry.
  describe('importing a Google calendar', { timeout: 30_000 }, () => {
    const GOOGLE_CALENDARS = [
      { id: 'primary@maggie.local', name: 'Défaut', summary: 'Fixture User', primary: true },
      { id: 'concerts@group.calendar.google.com', name: 'Mes concerts', summary: 'Concerts', primary: false },
    ]

    const serveAgendas = (agendas: unknown[]) => {
      mockGetList.mockImplementation((resource: string) => {
        if (resource === 'agendas') return Promise.resolve({ data: agendas, total: agendas.length })
        return Promise.resolve({ data: [], total: 0 })
      })
    }

    const openImportDialog = async () => {
      render(<CalendarView />)
      await userEvent.click(await screen.findByRole('button', { name: /Ajouter/ }))
      await userEvent.click(await screen.findByText('Importer depuis Google'))

      return screen.findByRole('dialog')
    }

    test('offers each calendar under the name it will carry, not Google’s raw summary', async () => {
      serveAgendas([])
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => GOOGLE_CALENDARS }))

      const dialog = await openImportDialog()

      expect(await within(dialog).findByText(/Défaut/)).toBeInTheDocument()
      expect(within(dialog).getByText('Mes concerts')).toBeInTheDocument()
      // The primary calendar's Google summary is the account holder's name.
      expect(within(dialog).queryByText('Fixture User')).not.toBeInTheDocument()
    })

    test('does not offer a calendar that is already connected', async () => {
      serveAgendas([
        { id: 'ag1', name: 'Mes concerts', googleCalendarId: 'concerts@group.calendar.google.com' },
      ])
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => GOOGLE_CALENDARS }))

      const dialog = await openImportDialog()

      expect(await within(dialog).findByText(/Défaut/)).toBeInTheDocument()
      expect(within(dialog).queryByText('Mes concerts')).not.toBeInTheDocument()
    })

    // The sidebar's only sign that an agenda is backed by Google. Asserted on
    // the handle the e2e journey uses, because MUI's own `data-testid` on an
    // icon is dev-only and the journey runs against the built bundle.
    test('badges the agendas Google backs, and only those', async () => {
      serveAgendas([
        { id: 'ag1', name: 'Mes concerts', googleCalendarId: 'concerts@group.calendar.google.com' },
        { id: 'ag2', name: 'Perso' },
      ])

      render(<CalendarView />)

      const google = (await screen.findAllByTestId('agenda-row')).find((row) =>
        row.textContent?.includes('Mes concerts'),
      )
      const local = (await screen.findAllByTestId('agenda-row')).find((row) =>
        row.textContent?.includes('Perso'),
      )

      expect(within(google!).getByTestId('agenda-sync-badge')).toBeInTheDocument()
      expect(within(google!).getByTitle('Synchronisé avec Google')).toBeInTheDocument()
      expect(within(local!).queryByTestId('agenda-sync-badge')).not.toBeInTheDocument()

      // The badge's accessible name is text inside the row, so the name has to
      // live in an element of its own — the journey matches a row on it, and
      // matching on the row's own text read "Mes concertsSynchronisé avec
      // Google" instead.
      expect(within(google!).getByTestId('agenda-name')).toHaveTextContent('Mes concerts')
      expect(within(google!).getByTestId('agenda-name').textContent).toBe('Mes concerts')
    })

    test('reports a Google calendar list that cannot be read', async () => {
      serveAgendas([])
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({}) }))

      await openImportDialog()

      await waitFor(() =>
        expect(mockNotify).toHaveBeenCalledWith(
          'Impossible de charger les calendriers Google',
          { type: 'error' },
        ),
      )
    })
  })
})
