import { describe, test, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EventCreateDialog } from './EventCreateDialog'

const mockGetList = vi.fn()
const mockCreate = vi.fn()
const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({ getList: mockGetList, create: mockCreate }),
  useNotify: () => mockNotify,
}))

const AGENDAS = [
  { id: '/api/agendas/01PERSO', name: 'Perso', default: true },
  { id: '/api/agendas/01FAMILLE', name: 'Famille', default: false },
]

/**
 * The dialog MAG-100's agenda journey creates every event through.
 *
 * What it sends matters more than how it looks: `c359b43` shipped a doubled Hydra
 * IRI from here — `calendarId` already *is* an IRI from the Hydra data provider,
 * and wrapping it in `/api/calendars/` again broke every creation — and the
 * payload is the only place a journey can see that.
 */
describe('EventCreateDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetList.mockResolvedValue({ data: AGENDAS, total: AGENDAS.length })
    mockCreate.mockResolvedValue({ data: {} })
  })

  const open = async () => {
    render(
      <EventCreateDialog
        open
        onClose={vi.fn()}
        onCreated={vi.fn()}
        defaultStart={new Date(2026, 9, 5, 15, 0)}
        defaultEnd={new Date(2026, 9, 5, 16, 0)}
      />,
    )
    // The agenda select is filled from `getList`; nothing can be submitted before.
    await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('agendas', expect.any(Object)))
  }

  test('pre-fills the slot that was selected on the grid', async () => {
    await open()

    expect(screen.getByLabelText(/Début/)).toHaveValue('2026-10-05T15:00')
    expect(screen.getByLabelText(/Fin/)).toHaveValue('2026-10-05T16:00')
  })

  /** MAG-649: the creation holds a place and a description, as the edition does, and keeps them. */
  test('creates an event with its place and its description', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Atelier poterie' } })
    fireEvent.change(screen.getByLabelText('Lieu'), { target: { value: 'Atelier des Lilas' } })
    expect(screen.getByRole('toolbar', { name: 'Barre de mise en forme' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'Apporter un tablier' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({
          data: expect.objectContaining({ location: 'Atelier des Lilas', description: 'Apporter un tablier' }),
        }),
      ),
    )
  })

  test('leaves the place and the description out when they are blank', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Café' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('location')
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('description')
  })

  test('posts the default agenda as the bare IRI the provider handed over', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Café avec Léa' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({
          data: expect.objectContaining({ summary: 'Café avec Léa', agenda: '/api/agendas/01PERSO' }),
        }),
      ),
    )
  })

  test('preselects the agenda flagged as default, not the first one listed (MAG-149)', async () => {
    mockGetList.mockResolvedValue({
      data: [
        { id: '/api/agendas/01CONCERTS', name: 'Concerts', default: false },
        { id: '/api/agendas/01PERSO', name: 'Perso', default: true },
      ],
      total: 2,
    })
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Concert' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({ data: expect.objectContaining({ agenda: '/api/agendas/01PERSO' }) }),
      ),
    )
  })

  // The list of agendas arrives after the dialog opens: until it does, no agenda is chosen and "Créer" must wait (MAG-397).
  test('does not post before the agendas are loaded, then posts the default one', async () => {
    let resolveAgendas: (value: { data: typeof AGENDAS; total: number }) => void = () => {}
    mockGetList.mockReturnValue(new Promise((resolve) => (resolveAgendas = resolve)))
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Concert' } })
    expect(screen.getByRole('button', { name: 'Créer' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Créer' }))
    expect(mockCreate).not.toHaveBeenCalled()

    resolveAgendas({ data: AGENDAS, total: AGENDAS.length })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Créer' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({ data: expect.objectContaining({ agenda: '/api/agendas/01PERSO' }) }),
      ),
    )
  })

  /**
   * A timed event is posted as an instant (MAG-168).
   *
   * `datetime-local` holds a wall-clock time in the browser's zone; PHP runs on UTC,
   * so an offsetless `2026-10-05T15:00:00` was stored as 15:00 UTC — 17:00 in Paris.
   * Asserted on the exact string, since `new Date(offsetless)` reads it as local and
   * could not see the difference. Summer and winter, so the offset is the right one.
   */
  test.each([
    ['summer', new Date(2026, 9, 5, 15, 0), new Date(2026, 9, 5, 16, 0)],
    ['winter', new Date(2026, 0, 12, 15, 0), new Date(2026, 0, 12, 16, 0)],
  ])('a timed event is posted as an explicit instant — %s (MAG-168)', async (_season, start, end) => {
    render(<EventCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} defaultStart={start} defaultEnd={end} />)
    await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('agendas', expect.any(Object)))

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Café avec Léa' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    const { startAt, endAt } = mockCreate.mock.calls[0][1].data as { startAt: string; endAt: string }

    expect(startAt).toBe(start.toISOString())
    expect(endAt).toBe(end.toISOString())
    expect(startAt).toMatch(/Z$/)
  })

  test('refuses an empty summary', async () => {
    await open()

    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    expect(mockCreate).not.toHaveBeenCalled()
    expect(screen.getByText('Le titre est requis')).toBeInTheDocument()
  })

  // An all-day event is a pair of dates, the end excluded as in Google, and no instant (MAG-382).
  test('an all-day event is posted as its dates, without startAt or endAt', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Anniversaire' } })
    await userEvent.click(screen.getByRole('switch', { name: 'Toute la journée' }))
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    const { data } = mockCreate.mock.calls[0][1] as { data: Record<string, unknown> }
    expect(data).toMatchObject({ allDay: true, startDate: '2026-10-05', endDate: '2026-10-06' })
    expect(data).not.toHaveProperty('startAt')
    expect(data).not.toHaveProperty('endAt')
  })

  test('a selection of three days shows its last day, and is posted with the day after', async () => {
    // FullCalendar's selection end is exclusive: 26 → 29 is the 26th, 27th and 28th.
    render(
      <EventCreateDialog
        open
        onClose={vi.fn()}
        onCreated={vi.fn()}
        defaultStart={new Date(2037, 0, 26)}
        defaultEnd={new Date(2037, 0, 29)}
        defaultAllDay
      />,
    )
    await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('agendas', expect.any(Object)))

    expect(screen.getByLabelText(/Fin/)).toHaveValue('2037-01-28')
    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Stage' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({
          data: expect.objectContaining({ allDay: true, startDate: '2037-01-26', endDate: '2037-01-29' }),
        }),
      ),
    )
  })

  test('a refused all-day event says why and keeps the dialog open', async () => {
    const onClose = vi.fn()
    mockCreate.mockRejectedValue(new Error('endDate: la fin précède le début'))
    render(
      <EventCreateDialog open onClose={onClose} onCreated={vi.fn()} defaultStart={new Date(2037, 0, 1)} defaultAllDay />,
    )
    await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('agendas', expect.any(Object)))

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Nouvel an' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockNotify).toHaveBeenCalledWith('Erreur: endDate: la fin précède le début', { type: 'error' }),
    )
    expect(onClose).not.toHaveBeenCalled()
  })

  /**
   * A reminder set in the dialog reaches the API in Google's shape (MAG-121).
   *
   * The payload is the whole point: the reminder cron reads `overrides`, so a
   * bare list — or nothing at all, which is what this dialog used to send
   * whatever the owner asked for — is a reminder that fires silently.
   */
  test('the reminder picker posts the delays, and nothing when none is added', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Dentiste' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('reminders')

    mockCreate.mockClear()
    await userEvent.click(screen.getByRole('combobox', { name: 'Rappels' }))
    await userEvent.click(screen.getByRole('option', { name: '1 heure avant' }))
    await userEvent.click(screen.getByRole('option', { name: '1 jour avant' }))
    await userEvent.keyboard('{Escape}')
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({
          data: expect.objectContaining({
            reminders: { useDefault: false, overrides: [
                { method: 'popup', minutes: 60 },
                { method: 'popup', minutes: 1440 },
              ],
            },
          }),
        }),
      ),
    )
  })

  test('an all-day event hides the reminders and sends none', async () => {
    await open()

    await userEvent.click(screen.getByRole('combobox', { name: 'Rappels' }))
    await userEvent.click(screen.getByRole('option', { name: '1 heure avant' }))
    await userEvent.keyboard('{Escape}')
    await userEvent.click(screen.getByLabelText('Toute la journée'))

    expect(screen.queryByRole('combobox', { name: 'Rappels' })).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Férié' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('reminders')
  })

  test('the recurrence picker adds an RRULE, and leaves it out when set to never', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Cours de piano' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('rrule')

    mockCreate.mockClear()
    await userEvent.click(screen.getByRole('combobox', { name: 'Répéter' }))
    await userEvent.click(screen.getByRole('option', { name: 'Toutes les semaines' }))
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({ data: expect.objectContaining({ rrule: expect.stringContaining('FREQ=WEEKLY') }) }),
      ),
    )
  })
  /** MAG-246: « Provisoire » is a choice of the form, confirmed unless the owner says otherwise. */
  test('creates a confirmed event unless the status is changed', async () => {
    await open()

    expect(screen.getByRole('combobox', { name: 'Statut' })).toHaveTextContent('Confirmé')
    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Déjeuner' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({ data: expect.objectContaining({ status: 'confirmed' }) }),
      ),
    )
  })

  test('creates a tentative event when « Provisoire » is chosen', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Déjeuner' } })
    await userEvent.click(screen.getByRole('combobox', { name: 'Statut' }))
    await userEvent.click(screen.getByRole('option', { name: 'Provisoire' }))
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({ data: expect.objectContaining({ status: 'tentative' }) }),
      ),
    )
  })

  test('says so when the API refuses the event', async () => {
    mockCreate.mockRejectedValue(new Error('Invalid status'))
    await open()

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Déjeuner' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Invalid status', { type: 'error' }))
  })
})
