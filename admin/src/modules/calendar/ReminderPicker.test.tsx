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

    expect(screen.getByRole('combobox', { name: 'Rappels' })).toHaveTextContent('Aucun rappel')
  })

  test('the menu lists the six Google options and « Personnaliser… »', async () => {
    render(<ReminderPicker value={null} onChange={vi.fn()} />)

    await userEvent.click(screen.getByRole('combobox', { name: 'Rappels' }))

    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([
      '5 minutes avant',
      '10 minutes avant',
      '15 minutes avant',
      '30 minutes avant',
      '1 heure avant',
      '1 jour avant',
      'Personnaliser…',
    ])
  })

  test('checking a second option emits both, in Google’s shape', async () => {
    const onChange = vi.fn()
    render(<ReminderPicker value={at(60)} onChange={onChange} />)

    await userEvent.click(screen.getByRole('combobox', { name: 'Rappels' }))
    await userEvent.click(screen.getByRole('option', { name: '1 jour avant' }))

    expect(onChange).toHaveBeenCalledWith(at(60, 1440))
  })

  test('unchecking the only reminder emits null, which is what clears the field', async () => {
    const onChange = vi.fn()
    render(<ReminderPicker value={at(60)} onChange={onChange} />)

    await userEvent.click(screen.getByRole('combobox', { name: 'Rappels' }))
    await userEvent.click(screen.getByRole('option', { name: '1 heure avant' }))

    expect(onChange).toHaveBeenCalledWith(null)
  })

  test('the field lists the chosen reminders', () => {
    render(<ReminderPicker value={at(60, 1440)} onChange={vi.fn()} />)

    expect(screen.getByRole('combobox', { name: 'Rappels' })).toHaveTextContent('1 heure avant, 1 jour avant')
  })

  test('« Personnaliser… » adds a free delay in days', async () => {
    const onChange = vi.fn()
    render(<ReminderPicker value={at(60)} onChange={onChange} />)

    await userEvent.click(screen.getByRole('combobox', { name: 'Rappels' }))
    await userEvent.click(screen.getByRole('option', { name: 'Personnaliser…' }))
    await userEvent.clear(screen.getByLabelText('Délai'))
    await userEvent.type(screen.getByLabelText('Délai'), '2')
    await userEvent.click(screen.getByRole('combobox', { name: 'Unité' }))
    await userEvent.click(screen.getByRole('option', { name: 'jours' }))
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    expect(onChange).toHaveBeenCalledWith(at(60, 2880))
  })

  /** Five is what Google accepts on one event, and what the API validates. */
  test('stops offering more once five are chosen', async () => {
    render(<ReminderPicker value={at(5, 10, 15, 30, 60)} onChange={vi.fn()} />)

    await userEvent.click(screen.getByRole('combobox', { name: 'Rappels' }))

    expect(screen.getByRole('option', { name: '1 jour avant' })).toHaveAttribute('aria-disabled', 'true')
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
