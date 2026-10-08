import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
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
