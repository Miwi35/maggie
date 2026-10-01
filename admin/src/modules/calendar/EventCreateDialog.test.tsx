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
  { id: '/api/agendas/01PERSO', name: 'Perso', isDefault: true },
  { id: '/api/agendas/01FAMILLE', name: 'Famille', isDefault: false },
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

  /**
   * A timed event is posted as an instant — MAG-168, and it is not.
   *
   * `datetime-local` holds a wall-clock time in the *browser's* zone, and this dialog
   * concatenates it as-is: `2026-10-05T15:00:00`, no offset. PHP runs on UTC
   * (`.docker/php/conf.d/app.ini`), so an event entered at 15:00 in Paris is stored
   * at 15:00 UTC — 17:00 for the owner. `EventEditDialog` does the opposite and is
   * right (`new Date(startAt).toISOString()`), so the two forms disagree: creating
   * shifts the event, reopening and saving it "corrects" it to the wrong hour.
   *
   * The offset is what is asserted, rather than the instant: JavaScript reads an
   * offsetless date-time as *local*, so `new Date(payload).getTime()` is the hour the
   * owner typed either way and cannot see the bug at all. Only PHP disagrees, and
   * what it needs is the offset.
   *
   * `test.fails()` rather than pinning the wrong payload as the expectation: one bug,
   * one convention — `e2e/web/tests/agenda-events.spec.ts` marks the same gap with
   * Playwright's `test.fail()`. It turns red the day MAG-168 lands, which is when
   * both markers go.
   */
  test.fails('a timed event is posted with an explicit offset — MAG-168', async () => {
    await open()

    fireEvent.change(screen.getByLabelText(/Résumé/), { target: { value: 'Café avec Léa' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    const { startAt, endAt } = mockCreate.mock.calls[0][1].data as { startAt: string; endAt: string }

    expect(startAt).toMatch(/([+-]\d{2}:\d{2}|Z)$/)
    expect(endAt).toMatch(/([+-]\d{2}:\d{2}|Z)$/)
  })

  test('refuses an empty summary', async () => {
    await open()

    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    expect(mockCreate).not.toHaveBeenCalled()
    expect(screen.getByText('Le résumé est requis')).toBeInTheDocument()
  })

  // Same offsetless shape as above, and harmless for a Paris owner: the dialog
  // hides the time on an all-day event, so a two-hour shift stays inside the day.
  // Part of MAG-168 all the same.
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
            startAt: '2026-10-05T00:00:00',
            endAt: '2026-10-05T23:59:59',
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
