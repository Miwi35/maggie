import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { forwardRef } from 'react'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarView } from './CalendarView'

/**
 * Maggie's proactions in the web agenda (MAG-328): timed events of her internal
 * « Maggie » agenda, served by `/api/proactions`, moved and cancelled through
 * `/api/events/{id}`.
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

// FullCalendar's drag is a pointer gesture jsdom cannot make: the props are kept so the drop can be called.
const renderedWith = vi.hoisted(() => vi.fn())
vi.mock('@fullcalendar/react', async (importOriginal) => {
  const { default: Original } = await importOriginal<typeof import('@fullcalendar/react')>()
  const Spy = forwardRef<unknown, Record<string, unknown>>((props, ref) => {
    renderedWith(props)
    return <Original ref={ref as never} {...(props as object)} />
  })
  Spy.displayName = 'FullCalendarSpy'
  return { default: Spy }
})

const mockGetList = vi.fn()
const mockUpdate = vi.fn()
const mockDelete = vi.fn()
const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    getOne: vi.fn(),
    create: vi.fn(),
    update: mockUpdate,
    delete: mockDelete,
  }),
  useNotify: () => mockNotify,
}))

const AGENDA = '/api/agendas/01PERSO'
const now = new Date()
const at = (hour: number, minute: number) => new Date(now.getFullYear(), now.getMonth(), 15, hour, minute).toISOString()
const fiveMinutesAfter = (hour: number, minute: number) => at(hour, minute + 5)

const proaction = (id: string, summary: string, status: string, extra: Record<string, unknown> = {}) => ({
  id: `/api/proactions/${id}`,
  summary,
  prompt: `Instruction complète : ${summary}`,
  startAt: at(8, 30),
  endAt: fiveMinutesAfter(8, 30),
  agenda: '/api/agendas/01MAGGIE',
  executionStatus: status,
  response: null,
  error: null,
  completedAt: null,
  ...extra,
})

let proactions: unknown[] = []

const serve = () => {
  mockGetList.mockImplementation((resource: string) => {
    const data: Record<string, unknown[]> = {
      agendas: [{ id: AGENDA, name: 'Perso', color: '#3f51b5', default: true }],
      events: [
        {
          id: '/api/events/01RDV',
          summary: 'Dentiste',
          startAt: at(10, 0),
          endAt: at(11, 0),
          allDay: false,
          agenda: AGENDA,
        },
      ],
      proactions,
    }
    const rows = data[resource] ?? []
    return Promise.resolve({ data: rows, total: rows.length })
  })
}

/** The `eventDrop` FullCalendar was given, called the way it calls it. */
const drop = (id: string, start: Date, allDay = false) => {
  const props = renderedWith.mock.calls.at(-1)?.[0] as { eventDrop: (arg: unknown) => void }
  const revert = vi.fn()
  act(() => {
    props.eventDrop({
      event: { id, start, end: null, allDay, extendedProps: { isProaction: true } },
      revert,
    })
  })
  return revert
}

describe('CalendarView — proactions', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    MockEventSource.instances = []
    proactions = [proaction('01PRO', 'Résumé de la journée', 'pending')]
    vi.stubGlobal('EventSource', MockEventSource)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: () => Promise.resolve({}) }))
    mockUpdate.mockResolvedValue({ data: {} })
    mockDelete.mockResolvedValue({ data: {} })
    serve()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('asks for the proactions of the visible range, in start order', async () => {
    render(<CalendarView />)

    await waitFor(() =>
      expect(mockGetList).toHaveBeenCalledWith(
        'proactions',
        expect.objectContaining({
          sort: { field: 'startAt', order: 'ASC' },
          filter: expect.objectContaining({ 'startAt[after]': expect.any(String), 'startAt[before]': expect.any(String) }),
        }),
      ),
    )
  })

  test('draws a proaction at its time with its label, beside the ordinary events', async () => {
    render(<CalendarView />)

    const label = await screen.findByText('Résumé de la journée')
    const chip = label.closest('.fc-event') as HTMLElement
    expect(within(chip).getByText('08:30')).toBeInTheDocument()
    expect(chip).not.toHaveTextContent('✓')
    expect(chip).not.toHaveTextContent('⚠')
    expect(screen.getByText('Dentiste')).toBeInTheDocument()
  })

  test('marks a proaction that ran, and one that failed', async () => {
    proactions = [
      proaction('01DONE', 'Appeler la crèche', 'completed', { startAt: at(7, 0), endAt: at(7, 5) }),
      proaction('01FAIL', 'Relancer le plombier', 'failed', { startAt: at(9, 0), endAt: at(9, 5) }),
    ]
    render(<CalendarView />)

    expect(await screen.findByText('✓ Appeler la crèche')).toBeInTheDocument()
    const failed = (await screen.findByText('⚠ Relancer le plombier')).closest('.fc-event') as HTMLElement
    expect(failed).toHaveClass('proaction-failed')
  })

  test('only a proaction still to run can be moved, and none can be stretched', async () => {
    proactions = [proaction('01PRO', 'À venir', 'pending'), proaction('01DONE', 'Faite', 'completed')]
    render(<CalendarView />)
    await screen.findByText('À venir')

    const props = renderedWith.mock.calls.at(-1)?.[0] as { events: { id: string; startEditable?: boolean; durationEditable?: boolean }[] }
    const byId = (id: string) => props.events.find((e) => e.id === id)
    expect(byId('proaction-01PRO')).toMatchObject({ startEditable: true, durationEditable: false })
    expect(byId('proaction-01DONE')).toMatchObject({ startEditable: false, durationEditable: false })
  })

  test('hiding the « Maggie » agenda takes the proactions off the grid', async () => {
    render(<CalendarView />)
    await screen.findByText('Résumé de la journée')

    await userEvent.click(within(screen.getByTestId('maggie-agenda-row')).getByText('Maggie'))

    await waitFor(() => expect(screen.queryByText('Résumé de la journée')).not.toBeInTheDocument())
    expect(screen.getByText('Dentiste')).toBeInTheDocument()

    await userEvent.click(within(screen.getByTestId('maggie-agenda-row')).getByText('Maggie'))
    expect(await screen.findByText('Résumé de la journée')).toBeInTheDocument()
  })

  test('the detail shows the instruction, the status and the answer', async () => {
    proactions = [proaction('01DONE', 'Appeler la crèche', 'completed', { response: 'Message laissé à la crèche.' })]
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('✓ Appeler la crèche'))

    expect(await screen.findByText('Instruction complète : Appeler la crèche')).toBeInTheDocument()
    expect(screen.getByText('Faite')).toBeInTheDocument()
    expect(screen.getByText('Message laissé à la crèche.')).toBeInTheDocument()
    const card = document.querySelector('.MuiPopover-paper') as HTMLElement
    expect(within(card).getByText('Maggie')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Modifier' })).not.toBeInTheDocument()
  })

  test('the detail of a failed proaction shows the error', async () => {
    proactions = [proaction('01FAIL', 'Relancer le plombier', 'failed', { error: 'Le calendrier est injoignable.' })]
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('⚠ Relancer le plombier'))

    expect(await screen.findByText('Échouée')).toBeInTheDocument()
    expect(screen.getByText('Le calendrier est injoignable.')).toBeInTheDocument()
  })

  test('cancelling it from the detail deletes the event of the same id', async () => {
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('Résumé de la journée'))
    await userEvent.click(await screen.findByRole('button', { name: 'Supprimer' }))

    await waitFor(() =>
      expect(mockDelete).toHaveBeenCalledWith('events', {
        id: '/api/events/01PRO',
        previousData: { id: '/api/events/01PRO' },
      }),
    )
    expect(mockNotify).toHaveBeenCalledWith('Proaction annulée', { type: 'success' })
    expect(mockDelete).not.toHaveBeenCalledWith('proactions', expect.anything())
  })

  test('says so when the cancellation is refused', async () => {
    mockDelete.mockRejectedValue(new Error('Forbidden'))
    render(<CalendarView />)

    await userEvent.click(await screen.findByText('Résumé de la journée'))
    await userEvent.click(await screen.findByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Forbidden', { type: 'error' }))
  })

  test('dragging it patches the event with its new start and nothing else', async () => {
    render(<CalendarView />)
    await screen.findByText('Résumé de la journée')
    const moved = new Date(now.getFullYear(), now.getMonth(), 16, 9, 15)

    const revert = drop('proaction-01PRO', moved)

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('events', {
        id: '/api/events/01PRO',
        data: { startAt: moved.toISOString() },
        previousData: { id: '/api/events/01PRO' },
      }),
    )
    expect(mockNotify).toHaveBeenCalledWith('Proaction déplacée', { type: 'success' })
    expect(revert).not.toHaveBeenCalled()
  })

  test('puts it back and says why when the API refuses the move', async () => {
    mockUpdate.mockRejectedValue(new Error('Une proaction ne peut pas être dans le passé'))
    render(<CalendarView />)
    await screen.findByText('Résumé de la journée')

    const revert = drop('proaction-01PRO', new Date(2001, 0, 1, 9, 0))

    await waitFor(() => expect(revert).toHaveBeenCalled())
    expect(mockNotify).toHaveBeenCalledWith('Erreur: Une proaction ne peut pas être dans le passé', { type: 'error' })
  })

  test('puts it back, with no request, when dropped on the all-day row', async () => {
    render(<CalendarView />)
    await screen.findByText('Résumé de la journée')

    const revert = drop('proaction-01PRO', new Date(now.getFullYear(), now.getMonth(), 16), true)

    expect(revert).toHaveBeenCalled()
    expect(mockUpdate).not.toHaveBeenCalled()
    expect(mockNotify).toHaveBeenCalledWith(expect.stringContaining('une heure'), { type: 'warning' })
  })

  test('a live update of a proaction reloads the agenda', { timeout: 15_000 }, async () => {
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
    try {
      render(<CalendarView />)
      const proactionCalls = () => mockGetList.mock.calls.filter(([resource]) => resource === 'proactions').length
      await waitFor(() => expect(proactionCalls()).toBeGreaterThan(0))
      await waitFor(() => expect(MockEventSource.instances.length).toBeGreaterThan(0))
      expect(MockEventSource.instances.some((source) => decodeURIComponent(source.url).includes('/users/u1/api/proactions/:id'))).toBe(true)
      const before = proactionCalls()

      proactions = [proaction('01PRO', 'Résumé de la journée', 'pending'), proaction('01NEW', 'Programmée à l’instant', 'pending', { startAt: at(12, 0), endAt: at(12, 5) })]
      MockEventSource.instances[0].onmessage?.({ data: '{}' } as MessageEvent)

      await waitFor(() => expect(proactionCalls()).toBeGreaterThan(before))
      expect(await screen.findByText('Programmée à l’instant')).toBeInTheDocument()
    } finally {
      localStorage.removeItem('user')
    }
  })

  test('the module view of the meals asks for no proaction', async () => {
    render(<CalendarView moduleKey="cookbook" />)

    await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('meals', expect.anything()))
    expect(mockGetList).not.toHaveBeenCalledWith('proactions', expect.anything())
  })
})
