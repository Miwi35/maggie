import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
const mockCreate = vi.fn()
const mockUpdate = vi.fn()
const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    getOne: vi.fn(),
    create: mockCreate,
    update: mockUpdate,
    delete: vi.fn(),
  }),
  useNotify: () => mockNotify,
}))

const AGENDA = '/api/agendas/01PERSO'

/** An all-day event as the API sends it since MAG-382: two dates, the end excluded as in Google, no instant. */
const allDay = (id: string, summary: string, startDate: string, endDate: string, extra: Record<string, unknown> = {}) => ({
  id: `/api/events/${id}`,
  summary,
  allDay: true,
  startDate,
  endDate,
  startAt: null,
  endAt: null,
  agenda: AGENDA,
  ...extra,
})

const STAGE = allDay('01STAGE', 'Stage de voile', '2037-01-26', '2037-01-29')
const NEW_YEAR = allDay('01NOUVELAN', 'Nouvel an', '2037-01-01', '2037-01-02')
const BIRTHDAY = allDay('01SACHA', 'Anniversaire de Sacha', '2001-01-20', '2001-01-21', { rrule: 'FREQ=YEARLY' })

const serve = (events: unknown[]) => {
  mockGetList.mockImplementation((resource: string) => {
    if (resource === 'agendas') {
      return Promise.resolve({ data: [{ id: AGENDA, name: 'Perso', color: '#3f51b5', default: true }], total: 1 })
    }
    if (resource === 'events') return Promise.resolve({ data: events, total: events.length })
    return Promise.resolve({ data: [], total: 0 })
  })
}

/** The month-view cell of a date. */
const cell = (container: HTMLElement, date: string) =>
  container.querySelector(`td[data-date="${date}"]`) as HTMLElement

/**
 * The day view shows one date: the all-day row is the whole question. Walked one
 * day at a time, as the e2e journeys do — FullCalendar does no layout in jsdom, so
 * how wide a bar is drawn in the month view cannot be read here.
 */
const openDayView = async () => {
  await userEvent.click(screen.getByRole('button', { name: 'Jour' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Jour' })).toHaveClass('MuiButton-contained'))
}
const nextDay = async () => {
  await userEvent.click(screen.getByLabelText('Jour suivant(e)'))
}

/**
 * All-day events in the admin grid (MAG-382).
 *
 * They were datetimes — `T00:00:00Z` → `T23:59:59Z` from this admin, the next
 * midnight from Google — and the grid put a one-day event on two days. They are now
 * a pair of dates, the end excluded as in Google, which FullCalendar takes as it is.
 */
describe('CalendarView — all-day events are dates', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Only `Date`: the timers stay real, so `waitFor` and the Mercure retries behave.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2037, 0, 15, 10, 0))
    vi.stubGlobal('EventSource', MockEventSource)
    mockCreate.mockResolvedValue({ data: {} })
    mockUpdate.mockResolvedValue({ data: {} })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('an event of the 1st sits in the cell of the 1st alone', async () => {
    serve([NEW_YEAR])
    const { container } = render(<CalendarView />)

    const title = await screen.findByText('Nouvel an')
    const bar = title.closest('.fc-event') as HTMLElement

    expect(cell(container, '2037-01-01')).toContainElement(bar)
    // One segment, starting and ending there — not a bar laid across into the 2nd.
    expect(bar).toHaveClass('fc-event-start', 'fc-event-end')
    expect(bar.closest('.fc-daygrid-event-harness-abs')).toBeNull()
    expect(screen.getAllByText('Nouvel an')).toHaveLength(1)
  })

  test('an event of the 1st is on the 1st in the day view, and gone on the 2nd', async () => {
    vi.setSystemTime(new Date(2037, 0, 1, 10, 0))
    serve([NEW_YEAR])
    render(<CalendarView />)
    await screen.findByText('Nouvel an')

    await openDayView()
    expect(await screen.findByText('Nouvel an')).toBeInTheDocument()

    await nextDay()
    await waitFor(() => expect(screen.queryByText('Nouvel an')).not.toBeInTheDocument())
  })

  test('an event from the 26th to the 28th covers those three days and not the 29th', async () => {
    vi.setSystemTime(new Date(2037, 0, 26, 10, 0))
    serve([STAGE])
    render(<CalendarView />)
    await screen.findByText('Stage de voile')

    await openDayView()
    expect(await screen.findByText('Stage de voile')).toBeInTheDocument()
    await nextDay()
    expect(await screen.findByText('Stage de voile')).toBeInTheDocument()
    await nextDay()
    expect(await screen.findByText('Stage de voile')).toBeInTheDocument()
    await nextDay()
    await waitFor(() => expect(screen.queryByText('Stage de voile')).not.toBeInTheDocument())
  })

  test('the card says « au 28 » for an end stored on the 29th (MAG-358)', async () => {
    serve([STAGE])
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('Stage de voile'))

    expect(await screen.findByText('Lundi 26 janvier 2037 – mercredi 28 janvier 2037')).toBeInTheDocument()
  })

  test('a yearly birthday falls on its date, keyed by that date for its exceptions', async () => {
    serve([BIRTHDAY])
    const { container } = render(<CalendarView />)

    const title = await screen.findByText('Anniversaire de Sacha')
    expect(cell(container, '2037-01-20')).toContainElement(title)
    expect(screen.getAllByText('Anniversaire de Sacha')).toHaveLength(1)
  })

  test('a cancelled exception keyed at midnight UTC of the date hides that occurrence', async () => {
    const cancelled = allDay('01ANNUL', 'Anniversaire de Sacha', '2037-01-20', '2037-01-21', {
      recurringEvent: BIRTHDAY.id,
      originalStartAt: '2037-01-20T00:00:00+00:00',
      status: 'cancelled',
    })
    const other = allDay('01AUTRE', 'Repère', '2037-01-21', '2037-01-22')
    serve([BIRTHDAY, cancelled, other])
    render(<CalendarView />)

    await screen.findByText('Repère')
    expect(screen.queryByText('Anniversaire de Sacha')).not.toBeInTheDocument()
  })

  test('the pencil saves an all-day event as its dates, with the instants nulled', async () => {
    serve([STAGE])
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('Stage de voile'))
    await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))

    const dialog = await screen.findByRole('dialog', { name: "Modifier l'événement" })
    expect(within(dialog).getByLabelText(/Début/)).toHaveValue('2037-01-26')
    expect(within(dialog).getByLabelText(/Fin/)).toHaveValue('2037-01-28')
    fireEvent.change(within(dialog).getByLabelText(/Fin/), { target: { value: '2037-01-30' } })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({
          id: STAGE.id,
          data: expect.objectContaining({
            allDay: true,
            startDate: '2037-01-26',
            // « au 30 » typed, the day after stored.
            endDate: '2037-01-31',
            startAt: null,
            endAt: null,
          }),
        }),
      ),
    )
  })

  test('a refused save says why', async () => {
    mockUpdate.mockRejectedValue(new Error('endDate précède startDate'))
    serve([STAGE])
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('Stage de voile'))
    await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))
    const dialog = await screen.findByRole('dialog', { name: "Modifier l'événement" })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: endDate précède startDate', { type: 'error' }))
  })

  test('deleting one occurrence of a birthday cancels its date, keyed at midnight UTC', async () => {
    serve([BIRTHDAY])
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('Anniversaire de Sacha'))
    await userEvent.click(await screen.findByRole('button', { name: 'Supprimer' }))
    const confirm = await screen.findByRole('dialog', { name: /l'événement récurrent$/ })
    await userEvent.click(within(confirm).getByLabelText('Cet événement'))
    await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    const { data } = mockCreate.mock.calls[0][1] as { data: Record<string, unknown> }
    expect(data).toMatchObject({
      allDay: true,
      startDate: '2037-01-20',
      endDate: '2037-01-21',
      originalStartAt: '2037-01-20T00:00:00+00:00',
      recurringEvent: BIRTHDAY.id,
      status: 'cancelled',
    })
    expect(data).not.toHaveProperty('startAt')
    expect(data).not.toHaveProperty('timeZone')
  })

  test('changing one occurrence of a birthday writes an all-day exception on its new dates', async () => {
    serve([BIRTHDAY])
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('Anniversaire de Sacha'))
    await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))
    const edit = await screen.findByRole('dialog', { name: "Modifier l'événement" })
    fireEvent.change(within(edit).getByLabelText(/Début/), { target: { value: '2037-01-24' } })
    fireEvent.change(within(edit).getByLabelText(/Fin/), { target: { value: '2037-01-24' } })
    await userEvent.click(within(edit).getByRole('button', { name: 'Enregistrer' }))
    const confirm = await screen.findByRole('dialog', { name: /l'événement récurrent$/ })
    await userEvent.click(within(confirm).getByLabelText('Cet événement'))
    await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).toMatchObject({
      allDay: true,
      startDate: '2037-01-24',
      endDate: '2037-01-25',
      originalStartAt: '2037-01-20T00:00:00+00:00',
      recurringEvent: BIRTHDAY.id,
    })
  })

  test('moving every occurrence of a birthday shifts the series by whole days', async () => {
    serve([BIRTHDAY])
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('Anniversaire de Sacha'))
    await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))
    const edit = await screen.findByRole('dialog', { name: "Modifier l'événement" })
    fireEvent.change(within(edit).getByLabelText(/Début/), { target: { value: '2037-01-22' } })
    fireEvent.change(within(edit).getByLabelText(/Fin/), { target: { value: '2037-01-22' } })
    await userEvent.click(within(edit).getByRole('button', { name: 'Enregistrer' }))
    const confirm = await screen.findByRole('dialog', { name: /l'événement récurrent$/ })
    await userEvent.click(within(confirm).getByLabelText('Tous les événements'))
    await userEvent.click(within(confirm).getByRole('button', { name: 'OK' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({
          id: BIRTHDAY.id,
          data: expect.objectContaining({
            allDay: true,
            startDate: '2001-01-22',
            endDate: '2001-01-23',
            startAt: null,
            endAt: null,
          }),
        }),
      ),
    )
  })
})
