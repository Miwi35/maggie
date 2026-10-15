import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RhythmCurve } from './RhythmCurve'
import { rhythm } from './scoresFixture'

describe('RhythmCurve', () => {
  test('marks each direct debit on its day', () => {
    render(<RhythmCurve rhythm={rhythm()} />)

    const marker = screen.getByTestId('debit-marker')
    expect(marker.dataset.label).toBe('Loyer')
    expect(marker.dataset.date).toBe('2026-10-05')
    expect(marker).toHaveTextContent('Loyer 5')
    expect(screen.getByText(/Loyer — le 5 —/)).toBeInTheDocument()
  })

  test('the spent line stops at today, the days to come have no point', () => {
    render(<RhythmCurve rhythm={rhythm()} />)

    const commands = screen.getByTestId('spent-line').getAttribute('d')?.match(/[ML]/g)
    expect(commands).toHaveLength(3)
  })

  test('a debit outside the drawn days is not drawn', () => {
    render(
      <RhythmCurve
        rhythm={rhythm({
          debits: [{ date: '2026-11-05', label: 'Autre', amountCents: 100, recurringOperationId: 'x' }],
        })}
      />,
    )

    expect(screen.queryByTestId('debit-marker')).not.toBeInTheDocument()
  })

  test('says in words what it draws', () => {
    render(<RhythmCurve rhythm={rhythm()} />)

    expect(screen.getByRole('img')).toHaveAccessibleName(/Dépensé cumulé/)
  })
})
