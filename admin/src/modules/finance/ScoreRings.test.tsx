import { describe, test, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { TOKENS } from '../../design/tokens'
import { ScoreRings } from './ScoreRings'
import { ringShares } from './useFinanceScores'
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
  test('shows what was spent and what is expected, with a tick and no verdict', () => {
    renderRings()

    const month = ring('month')
    expect(within(month).getByTestId('ring-spent')).toHaveTextContent(money(50000))
    expect(within(month).getByTestId('ring-expected')).toHaveTextContent(`Attendu ${money(60000)}`)
    expect(within(month).getByTestId('rhythm-tick')).toBeInTheDocument()
    expect(month).not.toHaveTextContent(/rythme|restants|Attention|Dépassement|Dans le vert|Dans les clous/)
  })

  test('spending more than expected is normal: same neutral colour, no status', () => {
    renderRings(
      scores({
        month: period({ status: 'neutral', rhythm: rhythm({ spentCents: 120000, expectedSpentCents: 78000, plannedCents: 81000 }) }),
      }),
    )

    const month = ring('month')
    expect(month).not.toHaveAttribute('data-status')
    expect(arc('month')).toHaveAttribute('stroke', theme.palette.primary.main)
    expect(arc('month')).not.toHaveAttribute('stroke', TOKENS.rhythm.above.dark)
    expect(month).not.toHaveTextContent(/rythme|Attention|Dépassement/)
    expect(month).toHaveTextContent(money(120000))
  })

  test('the arc and the ring name read the same in the light mode', () => {
    render(
      <ThemeProvider theme={veilleuseLightTheme}>
        <ScoreRings scores={scores()} />
      </ThemeProvider>,
    )

    expect(arc('month')).toHaveAttribute('stroke', veilleuseLightTheme.palette.primary.main)
    expect(within(ring('month')).getByRole('button')).toHaveAccessibleName(
      new RegExp(`Mois : ${money(50000)} dépensés, ${money(60000)} attendus`.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/ /g, '\\s')),
    )
  })

  test('touching a ring gives the reasons of its daily score, and touching again hides them', async () => {
    const user = userEvent.setup()
    renderRings()

    expect(screen.queryByTestId('score-reasons')).not.toBeInTheDocument()

    await user.click(within(ring('month')).getByRole('button'))
    expect(within(ring('month')).getByTestId('score-reasons')).toHaveTextContent(
      `${money(10000)} de moins qu'à la même période l'an dernier`,
    )
    expect(within(ring('week')).queryByTestId('score-reasons')).not.toBeInTheDocument()

    await user.click(within(ring('month')).getByRole('button'))
    expect(screen.queryByTestId('score-reasons')).not.toBeInTheDocument()
  })

  test('a ring with nothing planned draws no tick and no « Attendu »', () => {
    renderRings(
      scores({
        week: period({
          status: 'neutral',
          label: 'Dans les clous',
          reasons: [],
          rhythm: rhythm({ spentCents: 4500, expectedSpentCents: 0, plannedCents: 0, curve: [], debits: [] }),
        }),
      }),
    )

    expect(within(ring('week')).getByTestId('ring-spent')).toHaveTextContent(money(4500))
    expect(within(ring('week')).queryByTestId('rhythm-tick')).not.toBeInTheDocument()
    expect(within(ring('week')).queryByTestId('ring-expected')).not.toBeInTheDocument()
  })

  test('nothing spent draws no arc', () => {
    renderRings(scores({ week: period({ rhythm: rhythm({ spentCents: 0, expectedSpentCents: 0, plannedCents: 0 }) }) }))

    expect(arc('week')).toBeNull()
  })
})

describe('ScoreRings — debits nobody classified (MAG-643)', () => {
  const withUnclassified = (overrides = {}) =>
    rhythm({ spentCents: 250000, unclassifiedCount: 3, unclassifiedCents: 200000, ...overrides })

  const renderUnclassified = (value = scores({ month: period({ status: 'neutral', rhythm: withUnclassified() }) })) =>
    render(
      <MemoryRouter>
        <ThemeProvider theme={theme}>
          <ScoreRings scores={value} />
        </ThemeProvider>
      </MemoryRouter>,
    )

  test('they are in the spent amount, with no verdict', () => {
    renderUnclassified()

    expect(within(ring('month')).getByTestId('ring-spent')).toHaveTextContent(money(250000))
    expect(ring('month')).not.toHaveTextContent(/Rythme incomplet/)
  })

  test('under the ring: the weight of what is not classed and a link to the review filtered on it, for the month shown', () => {
    renderUnclassified()

    const line = within(ring('month')).getByTestId('ring-unclassified')
    expect(line).toHaveTextContent(`${money(200000)} non classés · Classer`)
    expect(within(line).getByTestId('ring-unclassified-link')).toHaveAttribute(
      'href',
      '/finance/review?state=unrecognised&month=2026-10',
    )
  })

  test('the line is absent from a ring whose debits are all classified', () => {
    renderUnclassified()

    expect(within(ring('week')).queryByTestId('ring-unclassified')).not.toBeInTheDocument()
    expect(within(ring('week')).queryByText('Classer')).not.toBeInTheDocument()
  })
})

describe('ringShares', () => {
  test('puts the arc on the share of the scale that was spent, and the tick on what is expected', () => {
    expect(ringShares({ spentCents: 50000, expectedSpentCents: 25000, plannedCents: 100000 })).toEqual({ spent: 0.5, expected: 0.25 })
  })

  test('spending past the plan fills the arc and moves the tick back on the same scale', () => {
    expect(ringShares({ spentCents: 120000, expectedSpentCents: 60000, plannedCents: 80000 })).toEqual({ spent: 1, expected: 0.5 })
  })

  test('without a plan there is no tick; without spending there is no arc', () => {
    expect(ringShares({ spentCents: 5000, expectedSpentCents: 0, plannedCents: 0 })).toEqual({ spent: 1, expected: null })
    expect(ringShares({ spentCents: 0, expectedSpentCents: 0, plannedCents: 0 })).toEqual({ spent: 0, expected: null })
  })
})
