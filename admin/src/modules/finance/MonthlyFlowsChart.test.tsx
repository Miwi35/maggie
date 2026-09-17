import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MonthlyFlowsChart } from './MonthlyFlowsChart'

const FLOWS = [
  { month: '2026-08', incomeCents: 350000, expenseCents: 120000, netCents: 230000 },
  { month: '2026-09', incomeCents: 350000, expenseCents: 25000, netCents: 325000 },
]

describe('MonthlyFlowsChart', () => {
  test('names both series so identity never rests on colour alone', () => {
    render(<MonthlyFlowsChart flows={FLOWS} />)

    expect(screen.getByText('Recettes')).toBeInTheDocument()
    expect(screen.getByText('Dépenses')).toBeInTheDocument()
  })

  test('labels every month under its bars', () => {
    render(<MonthlyFlowsChart flows={FLOWS} />)

    expect(screen.getByText('Août')).toBeInTheDocument()
    expect(screen.getByText('Sept.')).toBeInTheDocument()
  })

  test('invites the reader to hover before anything is hovered', () => {
    render(<MonthlyFlowsChart flows={FLOWS} />)

    expect(screen.getByText('Survolez un mois pour le détail.')).toBeInTheDocument()
  })

  test('hovering a month spells out its figures', async () => {
    const user = userEvent.setup()
    render(<MonthlyFlowsChart flows={FLOWS} />)

    await user.hover(screen.getByText('Sept.'))

    expect(await screen.findByText(/Sept\. :/)).toHaveTextContent('solde')
  })

  test('renders without bars when there is nothing to show', () => {
    render(<MonthlyFlowsChart flows={[]} />)

    expect(screen.getByText('Recettes')).toBeInTheDocument()
  })
})
