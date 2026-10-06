import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ReminderPicker, remindersFromMinutes, remindersToFrenchText, remindersToMinutes } from './ReminderPicker'
import type { EventReminders } from './ReminderPicker'

const at = (...minutes: number[]): EventReminders => ({
  useDefault: false,
  overrides: minutes.map((m) => ({ method: 'popup', minutes: m })),
})

/**
 * The reminders of an event, as the owner sets them (MAG-121).
 *
 * The shape it emits is the one the reminder cron reads — `{useDefault,
 * overrides}`. A bare list stores fine and fires nothing, so the conversion is
 * tested on its own rather than only through the dialogs.
 */
describe('ReminderPicker', () => {
  test('starts with no reminder and says so', () => {
    render(<ReminderPicker value={null} onChange={vi.fn()} />)

    expect(screen.getByText('Aucun rappel')).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Rappel' })).not.toBeInTheDocument()
  })

  test('adding a reminder emits Google’s shape', async () => {
    const onChange = vi.fn()
    render(<ReminderPicker value={null} onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: 'Ajouter un rappel' }))

    expect(onChange).toHaveBeenCalledWith(at(30))
  })

  test('a second reminder is added beside the first, not instead of it', async () => {
    const onChange = vi.fn()
    render(<ReminderPicker value={at(30)} onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: 'Ajouter un rappel' }))

    expect(onChange).toHaveBeenCalledWith(at(30, 5))
  })

  test('removing the only reminder emits null, which is what clears the field', async () => {
    const onChange = vi.fn()
    render(<ReminderPicker value={at(60)} onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: 'Supprimer le rappel 1 heure avant' }))

    expect(onChange).toHaveBeenCalledWith(null)
  })

  test('a delay already chosen cannot be chosen twice', async () => {
    render(<ReminderPicker value={at(30, 60)} onChange={vi.fn()} />)

    await userEvent.click(screen.getAllByRole('combobox', { name: 'Rappel' })[0])

    // Its own value stays selectable; the other row's does not.
    expect(screen.getByRole('option', { name: '30 minutes avant' })).not.toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('option', { name: '1 heure avant' })).toHaveAttribute('aria-disabled', 'true')
  })

  /** Five is what Google accepts on one event, and what the API validates. */
  test('stops offering more than five reminders', () => {
    render(<ReminderPicker value={at(5, 10, 15, 30, 60)} onChange={vi.fn()} />)

    expect(screen.queryByRole('button', { name: 'Ajouter un rappel' })).not.toBeInTheDocument()
  })

  test('reads back what the cron reads, and ignores the rest', () => {
    expect(remindersToMinutes(at(10, 60))).toEqual([10, 60])
    expect(remindersToMinutes(null)).toEqual([])
    // Zero means "no reminder" to the cron, so it is not one here either.
    expect(remindersToMinutes(at(0))).toEqual([])
    expect(remindersFromMinutes([])).toBeNull()
  })

  test('says the reminders in French, for the event card', () => {
    expect(remindersToFrenchText(at(30, 1440))).toBe('30 minutes avant, 1 jour avant')
    expect(remindersToFrenchText(null)).toBeNull()
  })
})
