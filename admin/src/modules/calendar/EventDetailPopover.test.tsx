import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EventDetailPopover } from './EventDetailPopover'
import type { PopoverEvent } from './EventDetailPopover'

const event: PopoverEvent = {
  id: '01EVENT',
  title: 'Déjeuner',
  start: new Date(2026, 9, 15, 12, 0).toISOString(),
  end: new Date(2026, 9, 15, 13, 0).toISOString(),
  allDay: false,
  color: '#4285f4',
  calendarName: 'Perso',
}

const renderPopover = (overrides: Partial<PopoverEvent> = {}) =>
  render(
    <EventDetailPopover
      event={{ ...event, ...overrides }}
      anchorEl={document.body}
      onClose={vi.fn()}
      onEdit={vi.fn()}
      onDelete={vi.fn()}
    />,
  )

/** MAG-246 */
describe('EventDetailPopover', () => {
  test('labels a tentative event « Provisoire »', () => {
    renderPopover({ status: 'tentative' })

    expect(screen.getByText('Provisoire')).toBeInTheDocument()
  })

  test.each([['confirmed' as const], [undefined]])('shows no label for a confirmed event (%s)', (status) => {
    renderPopover({ status })

    expect(screen.getByText('Déjeuner')).toBeInTheDocument()
    expect(screen.queryByText('Provisoire')).not.toBeInTheDocument()
  })
})

/** An all-day event shows its last day included, from the exclusive end it is stored with (MAG-358, MAG-382). */
describe('EventDetailPopover — all-day', () => {
  test('says « au 28 » for an event from the 26th to the 28th', () => {
    renderPopover({ allDay: true, start: '2037-01-26', end: '2037-01-29' })

    expect(screen.getByText('Lundi 26 janvier 2037 – mercredi 28 janvier 2037')).toBeInTheDocument()
  })

  test('shows a one-day event on its own date, the 1st and not the eve or the day after', () => {
    renderPopover({ allDay: true, start: '2037-01-01', end: '2037-01-02' })

    expect(screen.getByText('Jeudi 1 janvier 2037')).toBeInTheDocument()
  })

  test('the pencil and the bin act on the event shown', async () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(
      <EventDetailPopover
        event={{ ...event, allDay: true, start: '2037-01-01', end: '2037-01-02' }}
        anchorEl={document.body}
        onClose={vi.fn()}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Modifier' }))
    await userEvent.click(screen.getByRole('button', { name: 'Supprimer' }))

    expect(onEdit).toHaveBeenCalledWith('01EVENT')
    expect(onDelete).toHaveBeenCalledWith('01EVENT')
  })
})

/** MAG-328 */
describe('EventDetailPopover — proaction', () => {
  const proaction = { prompt: 'Rappelle-moi de déposer Ael', executionStatus: 'completed', response: null, error: null }

  test('shows the instruction, the status in French and the answer', () => {
    renderPopover({ proaction: { ...proaction, response: 'C’est fait : rappel envoyé.' } })

    expect(screen.getByText('Rappelle-moi de déposer Ael')).toBeInTheDocument()
    expect(screen.getByText('Faite')).toBeInTheDocument()
    expect(screen.getByText('C’est fait : rappel envoyé.')).toBeInTheDocument()
    expect(screen.queryByText('Erreur')).not.toBeInTheDocument()
  })

  test('shows the error of a run that failed', () => {
    renderPopover({ proaction: { ...proaction, executionStatus: 'failed', error: 'Le service est indisponible.' } })

    expect(screen.getByText('Échouée')).toBeInTheDocument()
    expect(screen.getByText('Le service est indisponible.')).toBeInTheDocument()
    expect(screen.queryByText('Réponse')).not.toBeInTheDocument()
  })

  test('has no pencil, but the bin cancels it', async () => {
    const onDelete = vi.fn()
    render(
      <EventDetailPopover event={{ ...event, proaction }} anchorEl={document.body} onClose={vi.fn()} onEdit={vi.fn()} onDelete={onDelete} />,
    )

    expect(screen.queryByRole('button', { name: 'Modifier' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Supprimer' }))
    expect(onDelete).toHaveBeenCalledWith('01EVENT')
  })

  test('shows nothing of it on an ordinary event', () => {
    renderPopover()

    expect(screen.queryByText('Instruction')).not.toBeInTheDocument()
  })
})
