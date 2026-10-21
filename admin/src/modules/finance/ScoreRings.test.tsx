import { describe, test, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { TOKENS } from '../../design/tokens'
import { ScoreRings } from './ScoreRings'
import { remainingShare } from './useFinanceScores'
import { formatCents } from './accountTypes'
import { period, rhythm, scores } from './scoresFixture'

// toHaveTextContent folds the narrow no-break spaces of the currency into plain ones.
const money = (cents: number) => formatCents(cents).replace(/\s/g, ' ')

const ring = (name: 'week' | 'month') =>
  document.querySelector(`[data-testid="score-ring"][data-period="${name}"]`) as HTMLElement

const theme = veilleuseDarkTheme

const renderRings = (value = scores()) =>
  render(
    <ThemeProvider theme={theme}>
      <ScoreRings scores={value} />
    </ThemeProvider>,
  )

const arc = (name: 'week' | 'month') => ring(name).querySelector('circle:nth-of-type(2)') as SVGCircleElement

describe('ScoreRings', () => {
  test('shows what is left, where it should be today, and the rhythm status in words', () => {
    renderRings()

    const month = ring('month')
    expect(month.dataset.status).toBe('on_track')
    expect(within(month).getByTestId('ring-remaining')).toHaveTextContent(money(50000))
    expect(within(month).getByTestId('ring-expected')).toHaveTextContent(
      `Devrait rester ${money(40000)}`,
    )
    expect(within(month).getByText('Dans le rythme')).toBeInTheDocument()
    expect(within(month).getByTestId('rhythm-tick')).toBeInTheDocument()
  })

  test('in the rhythm: the arc and the pastille are mint, with a tick', () => {
    renderRings()

    expect(arc('month')).toHaveAttribute('stroke', TOKENS.rhythm.onTrack.dark)
    expect(TOKENS.rhythm.onTrack.dark).toBe('#7FD1AE')
    expect(within(ring('month')).getByTestId('ring-pastille')).toHaveTextContent('✓')
    expect(within(ring('month')).getByTestId('ring-pastille')).toHaveStyle({
      backgroundColor: TOKENS.rhythm.onTrack.dark,
    })
  })

  test('above the rhythm: the label, the arc and the pastille are orange, with an attention mark', () => {
    renderRings(
      scores({
        week: period({
          status: 'orange',
          label: 'Attention',
          reasons: [{ code: 'above_rhythm', amountCents: 8000 }],
          rhythm: rhythm({ status: 'above', label: 'Au-dessus du rythme', spentCents: 68000 }),
        }),
      }),
    )

    const week = ring('week')
    expect(week.dataset.status).toBe('above')
    expect(within(week).getByText('Au-dessus du rythme')).toBeInTheDocument()
    expect(within(week).queryByText('Attention')).not.toBeInTheDocument()
    expect(arc('week')).toHaveAttribute('stroke', TOKENS.rhythm.above.dark)
    expect(TOKENS.rhythm.above.dark).toBe('#F0A35E')
    expect(within(week).getByTestId('ring-pastille')).toHaveTextContent('!')
    expect(within(week).getByTestId('ring-pastille')).toHaveStyle({
      backgroundColor: TOKENS.rhythm.above.dark,
    })
  })

  test('in the light mode the same two statuses are drawn with the light tokens', () => {
    render(
      <ThemeProvider theme={veilleuseLightTheme}>
        <ScoreRings scores={scores()} />
      </ThemeProvider>,
    )

    expect(arc('month')).toHaveAttribute('stroke', TOKENS.rhythm.onTrack.light)
  })

  test('the daily score never reaches the ring: no « Dans le vert », « Dans les clous », « Attention » or « Dépassement »', () => {
    renderRings()

    for (const word of ['Dans le vert', 'Dans les clous', 'Attention', 'Dépassement']) {
      expect(screen.queryByText(word)).not.toBeInTheDocument()
    }
    expect(ring('week').dataset.status).toBe('on_track')
  })

  test('touching a ring gives the reasons of its status, and touching again hides them', async () => {
    const user = userEvent.setup()
    renderRings()

    expect(screen.queryByTestId('score-reasons')).not.toBeInTheDocument()

    await user.click(within(ring('month')).getByRole('button'))
    expect(within(ring('month')).getByTestId('score-reasons')).toHaveTextContent(
      `${money(10000)} de moins que le rythme attendu à ce jour`,
    )
    expect(within(ring('week')).queryByTestId('score-reasons')).not.toBeInTheDocument()

    await user.click(within(ring('month')).getByRole('button'))
    expect(screen.queryByTestId('score-reasons')).not.toBeInTheDocument()
  })

  test('an overspent period shows a negative remainder and draws no arc', () => {
    renderRings(
      scores({
        month: period({
          status: 'red',
          label: 'Dépassement',
          reasons: [{ code: 'total_budget_exceeded', amountCents: 5000 }],
          rhythm: rhythm({
            status: 'above',
            label: 'Au-dessus du rythme',
            remainingCents: -5000,
            spentCents: 105000,
          }),
        }),
      }),
    )

    expect(within(ring('month')).getByTestId('ring-remaining')).toHaveTextContent(money(-5000))
    expect(within(ring('month')).getByText('Au-dessus du rythme')).toBeInTheDocument()
    expect(arc('month')).toBeNull()
  })

  test('a period with no budget says so instead of drawing a ring of nothing', () => {
    renderRings(
      scores({
        week: period({
          status: 'neutral',
          label: 'Dans les clous',
          reasons: [{ code: 'no_budget' }],
          rhythm: rhythm({ budgetCents: 0, remainingCents: 0, expectedRemainingCents: 0, curve: [], debits: [] }),
        }),
      }),
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
