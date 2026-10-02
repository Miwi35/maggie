import { describe, test, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EventCreateDialog } from './EventCreateDialog'

const mockGetList = vi.fn()
const mockCreate = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({ getList: mockGetList, create: mockCreate }),
  useNotify: () => vi.fn(),
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

  test('posts the default agenda as the bare IRI the provider handed over', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Résumé/), { target: { value: 'Café avec Léa' } })
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

    fireEvent.change(screen.getByLabelText(/Résumé/), { target: { value: 'Concert' } })
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

    fireEvent.change(screen.getByLabelText(/Résumé/), { target: { value: 'Café avec Léa' } })
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
    expect(screen.getByText('Le résumé est requis')).toBeInTheDocument()
  })

  // An all-day event is a calendar day, not an instant: it is stored as that day in
  // UTC (what Google sync does), with the zone written out (MAG-168).
  test('an all-day event is posted as whole days', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Résumé/), { target: { value: 'Anniversaire' } })
    await userEvent.click(screen.getByRole('switch', { name: 'Journée entière' }))
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({
          data: expect.objectContaining({
            allDay: true,
            startAt: '2026-10-05T00:00:00Z',
            endAt: '2026-10-05T23:59:59Z',
          }),
        }),
      ),
    )
  })

  test('the recurrence picker adds an RRULE, and leaves it out when set to never', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Résumé/), { target: { value: 'Cours de piano' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('rrule')

    mockCreate.mockClear()
    await userEvent.click(screen.getByRole('combobox', { name: 'Récurrence' }))
    await userEvent.click(screen.getByRole('option', { name: 'Toutes les semaines' }))
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'events',
        expect.objectContaining({ data: expect.objectContaining({ rrule: expect.stringContaining('FREQ=WEEKLY') }) }),
      ),
    )
  })
})
