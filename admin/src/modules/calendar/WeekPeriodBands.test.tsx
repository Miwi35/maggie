import { describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { WeekPeriodBands } from './WeekPeriodBands'
import type { Period } from './periodsApi'

// Monday 2026-10-19 → Sunday 2026-10-25
const WEEK = '2026-10-19'
const GARDE: Period = { id: '01G', name: 'Garde', agenda: '/api/agendas/a', allDay: true, startDate: '2026-10-20', endDate: '2026-10-25' }

describe('WeekPeriodBands (MAG-816)', () => {
  test('a five-day period spans five columns', () => {
    render(<WeekPeriodBands periods={[GARDE]} weekStart={WEEK} agendas={[{ id: '/api/agendas/a', color: '#e91e63' }]} onOpen={() => {}} />)

    const band = screen.getByTestId('period-band')
    expect(band).toHaveAttribute('data-column', '2')
    expect(band).toHaveAttribute('data-span', '5')
    expect(band).toHaveTextContent('Garde')
    expect(band).toHaveTextContent(/jusqu'au sam\. 24\/10/)
  })

  test('a period starting before the week is cut at its first column', () => {
    const before: Period = { ...GARDE, startDate: '2026-10-10', endDate: '2026-10-21' }
    render(<WeekPeriodBands periods={[before]} weekStart={WEEK} agendas={[]} onOpen={() => {}} />)

    const band = screen.getByTestId('period-band')
    expect(band).toHaveAttribute('data-column', '1')
    expect(band).toHaveAttribute('data-span', '2')
  })

  test('a period outside the week draws no band', () => {
    render(<WeekPeriodBands periods={[{ ...GARDE, startDate: '2026-11-02', endDate: '2026-11-03' }]} weekStart={WEEK} agendas={[]} onOpen={() => {}} />)

    expect(screen.queryByTestId('period-band')).toBeNull()
  })

  test('a click opens the dialog', async () => {
    const onOpen = vi.fn()
    render(<WeekPeriodBands periods={[GARDE]} weekStart={WEEK} agendas={[]} onOpen={onOpen} />)

    await userEvent.click(screen.getByTestId('period-band'))

    expect(onOpen).toHaveBeenCalledWith(GARDE)
  })
})
