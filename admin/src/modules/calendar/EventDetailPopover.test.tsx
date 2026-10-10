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

/** An all-day event shows its dates as they are stored: the last one is included (MAG-358, MAG-382). */
describe('EventDetailPopover — all-day', () => {
  test('says « au 28 » for an event from the 26th to the 28th', () => {
    renderPopover({ allDay: true, start: '2037-01-26', end: '2037-01-28' })

    expect(screen.getByText('Lundi 26 janvier 2037 – mercredi 28 janvier 2037')).toBeInTheDocument()
  })

  test('shows a one-day event on its own date, the 1st and not the eve or the day after', () => {
    renderPopover({ allDay: true, start: '2037-01-01', end: '2037-01-01' })

    expect(screen.getByText('Jeudi 1 janvier 2037')).toBeInTheDocument()
  })

  test('the pencil and the bin act on the event shown', async () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(
      <EventDetailPopover
        event={{ ...event, allDay: true, start: '2037-01-01', end: '2037-01-01' }}
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
