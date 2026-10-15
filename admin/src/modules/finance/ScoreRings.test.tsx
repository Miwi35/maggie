import { describe, test, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ScoreRings } from './ScoreRings'
import { remainingShare } from './useFinanceScores'
import { formatCents } from './accountTypes'
import { period, rhythm, scores } from './scoresFixture'

// toHaveTextContent folds the narrow no-break spaces of the currency into plain ones.
const money = (cents: number) => formatCents(cents).replace(/\s/g, ' ')

const ring = (name: 'week' | 'month') =>
  document.querySelector(`[data-testid="score-ring"][data-period="${name}"]`) as HTMLElement

describe('ScoreRings', () => {
  test('shows what is left, where it should be today, and the status in words', () => {
    render(<ScoreRings scores={scores()} />)

    const month = ring('month')
    expect(month.dataset.status).toBe('green')
    expect(within(month).getByTestId('ring-remaining')).toHaveTextContent(money(50000))
    expect(within(month).getByTestId('ring-expected')).toHaveTextContent(
      `Devrait rester ${money(40000)}`,
    )
    expect(within(month).getByText('Dans le vert')).toBeInTheDocument()
    expect(within(month).getByTestId('rhythm-tick')).toBeInTheDocument()

    expect(ring('week').dataset.status).toBe('neutral')
    expect(within(ring('week')).getByText('Dans les clous')).toBeInTheDocument()
  })

  test('touching a ring gives the reasons of its status, and touching again hides them', async () => {
    const user = userEvent.setup()
    render(<ScoreRings scores={scores()} />)

    expect(screen.queryByTestId('score-reasons')).not.toBeInTheDocument()

    await user.click(within(ring('month')).getByRole('button'))
    expect(within(ring('month')).getByTestId('score-reasons')).toHaveTextContent(
      `${money(10000)} de moins que le rythme attendu à ce jour`,
    )
    expect(within(ring('week')).queryByTestId('score-reasons')).not.toBeInTheDocument()

    await user.click(within(ring('month')).getByRole('button'))
    expect(screen.queryByTestId('score-reasons')).not.toBeInTheDocument()
  })

  test('an overspent period reads red with a negative remainder', () => {
    render(
      <ScoreRings
        scores={scores({
          month: period({
            status: 'red',
            label: 'Dépassement',
            reasons: [{ code: 'total_budget_exceeded', amountCents: 5000 }],
            rhythm: rhythm({ remainingCents: -5000, spentCents: 105000 }),
          }),
        })}
      />,
    )

    expect(ring('month').dataset.status).toBe('red')
    expect(within(ring('month')).getByTestId('ring-remaining')).toHaveTextContent(
      money(-5000),
    )
    expect(within(ring('month')).getByText('Dépassement')).toBeInTheDocument()
  })

  test('a period with no budget says so instead of drawing a ring of nothing', () => {
    render(
      <ScoreRings
        scores={scores({
          week: period({
            status: 'neutral',
            label: 'Dans les clous',
            reasons: [{ code: 'no_budget' }],
            rhythm: rhythm({ budgetCents: 0, remainingCents: 0, expectedRemainingCents: 0, curve: [], debits: [] }),
          }),
        })}
      />,
    )

    expect(within(ring('week')).getByText('Aucune enveloppe')).toBeInTheDocument()
    expect(within(ring('week')).queryByTestId('rhythm-tick')).not.toBeInTheDocument()
  })
})

describe('remainingShare', () => {
  test('is the share of the budget left, kept between nothing and everything', () => {
    expect(remainingShare(25000, 100000)).toBe(0.25)
    expect(remainingShare(-1, 100000)).toBe(0)
    expect(remainingShare(150000, 100000)).toBe(1)
    expect(remainingShare(500, 0)).toBe(0)
  })
})
