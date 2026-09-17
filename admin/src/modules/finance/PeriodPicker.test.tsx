import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PeriodPicker } from './PeriodPicker'

describe('PeriodPicker', () => {
  test('shows the month by name and the year as a number', () => {
    render(
      <PeriodPicker year={2026} month={9} onYearChange={vi.fn()} onMonthChange={vi.fn()} />,
    )

    expect(screen.getByText('Septembre')).toBeInTheDocument()
    expect(screen.getByDisplayValue('2026')).toBeInTheDocument()
  })

  test('reports the month the reader picks', async () => {
    const onMonthChange = vi.fn()
    const user = userEvent.setup()
    render(
      <PeriodPicker year={2026} month={9} onYearChange={vi.fn()} onMonthChange={onMonthChange} />,
    )

    await user.click(screen.getByLabelText('Mois'))
    await user.click(await screen.findByRole('option', { name: 'Octobre' }))

    expect(onMonthChange).toHaveBeenCalledWith(10)
  })

  test('reports the year the reader types', async () => {
    const onYearChange = vi.fn()
    const user = userEvent.setup()
    render(
      <PeriodPicker year={2026} month={9} onYearChange={onYearChange} onMonthChange={vi.fn()} />,
    )

    await user.type(screen.getByLabelText('Année'), '7')

    expect(onYearChange).toHaveBeenCalled()
  })
})
