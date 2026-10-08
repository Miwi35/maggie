import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EventEditDialog } from './EventEditDialog'

const event = {
  summary: 'Dentiste',
  start: new Date(2026, 9, 5, 14, 30).toISOString(),
  end: new Date(2026, 9, 5, 15, 30).toISOString(),
  allDay: false,
  location: 'Cabinet',
  description: 'Contrôle annuel',
}

describe('EventEditDialog', () => {
  test('pre-fills the form with the event', () => {
    render(<EventEditDialog open event={event} onClose={vi.fn()} onSubmit={vi.fn()} />)

    expect(screen.getByLabelText(/Résumé/)).toHaveValue('Dentiste')
    expect(screen.getByLabelText(/Début/)).toHaveValue('2026-10-05T14:30')
    expect(screen.getByLabelText(/Fin/)).toHaveValue('2026-10-05T15:30')
    expect(screen.getByLabelText('Lieu')).toHaveValue('Cabinet')
    expect(screen.getByLabelText('Description')).toHaveValue('Contrôle annuel')
  })

  test('submits the edited values', async () => {
    const onSubmit = vi.fn()
    render(<EventEditDialog open event={event} onClose={vi.fn()} onSubmit={onSubmit} />)

    const summary = screen.getByLabelText(/Résumé/)
    await userEvent.clear(summary)
    await userEvent.type(summary, 'Dentiste (contrôle)')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(onSubmit).toHaveBeenCalledWith({
      summary: 'Dentiste (contrôle)',
      startAt: event.start,
      endAt: event.end,
      allDay: false,
      description: 'Contrôle annuel',
      location: 'Cabinet',
      reminders: null,
      status: 'confirmed',
    })
  })

  /**
   * The reminders the event holds are shown, changed and sent back (MAG-121).
   *
   * Prefilling them is what makes the field editable at all: a dialog that opened
   * empty would silently drop every reminder the owner had — set from Maggie, or
   * imported from Google — the next time he renamed the event.
   */
  test('pre-fills the reminders and submits the one chosen instead', async () => {
    const onSubmit = vi.fn()
    render(
      <EventEditDialog
        open
        event={{ ...event, reminders: { useDefault: false, overrides: [{ method: 'popup', minutes: 30 }] } }}
        onClose={vi.fn()}
        onSubmit={onSubmit}
      />,
    )

    expect(screen.getByRole('combobox', { name: 'Rappel' })).toHaveTextContent('30 minutes avant')

    await userEvent.click(screen.getByRole('combobox', { name: 'Rappel' }))
    await userEvent.click(screen.getByRole('option', { name: '1 jour avant' }))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        reminders: { useDefault: false, overrides: [{ method: 'popup', minutes: 1440 }] },
      }),
    )
  })

  /** Removing the last reminder sends null, which is what clears the field. */
  test('removing the last reminder submits none', async () => {
    const onSubmit = vi.fn()
    render(
      <EventEditDialog
        open
        event={{ ...event, reminders: { useDefault: false, overrides: [{ method: 'popup', minutes: 30 }] } }}
        onClose={vi.fn()}
        onSubmit={onSubmit}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Supprimer le rappel 30 minutes avant' }))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(screen.getByText('Aucun rappel')).toBeInTheDocument()
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ reminders: null }))
  })

  test('sends blank optional fields as null', async () => {
    const onSubmit = vi.fn()
    render(
      <EventEditDialog open event={{ ...event, location: undefined, description: undefined }} onClose={vi.fn()} onSubmit={onSubmit} />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ description: null, location: null }))
  })

  test('refuses an empty summary', async () => {
    const onSubmit = vi.fn()
    render(<EventEditDialog open event={event} onClose={vi.fn()} onSubmit={onSubmit} />)

    await userEvent.clear(screen.getByLabelText(/Résumé/))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText('Le résumé est requis')).toBeInTheDocument()
  })

  test('shows an all-day event on its included days', () => {
    render(
      <EventEditDialog
        open
        event={{
          ...event,
          allDay: true,
          start: new Date(2026, 9, 5).toISOString(),
          end: new Date(2026, 9, 7).toISOString(),
        }}
        onClose={vi.fn()}
        onSubmit={vi.fn()}
      />,
    )

    expect(screen.getByLabelText(/Début/)).toHaveValue('2026-10-05')
    expect(screen.getByLabelText(/Fin/)).toHaveValue('2026-10-06')
  })

  test('submits an all-day event as whole days in UTC (MAG-168)', async () => {
    const onSubmit = vi.fn()
    render(
      <EventEditDialog
        open
        event={{ ...event, allDay: true, start: new Date(2026, 9, 5).toISOString(), end: new Date(2026, 9, 7).toISOString() }}
        onClose={vi.fn()}
        onSubmit={onSubmit}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ allDay: true, startAt: '2026-10-05T00:00:00Z', endAt: '2026-10-06T23:59:59Z' }),
    )
  })

  test('cancel closes without submitting', async () => {
    const onClose = vi.fn()
    const onSubmit = vi.fn()
    render(<EventEditDialog open event={event} onClose={onClose} onSubmit={onSubmit} />)

    await userEvent.click(screen.getByRole('button', { name: 'Annuler' }))

    expect(onClose).toHaveBeenCalled()
    expect(onSubmit).not.toHaveBeenCalled()
  })
  /** MAG-246: a tentative event opens as such, and goes back to confirmed from the form. */
  test('pre-fills the status and submits the one chosen instead', async () => {
    const onSubmit = vi.fn()
    render(<EventEditDialog open event={{ ...event, status: 'tentative' }} onClose={vi.fn()} onSubmit={onSubmit} />)

    expect(screen.getByRole('combobox', { name: 'Statut' })).toHaveTextContent('Provisoire')

    await userEvent.click(screen.getByRole('combobox', { name: 'Statut' }))
    await userEvent.click(screen.getByRole('option', { name: 'Confirmé' }))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ status: 'confirmed' }))
  })
})
