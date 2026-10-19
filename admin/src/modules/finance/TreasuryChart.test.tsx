import { describe, test, expect } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TreasuryChart, TreasuryFlowsChart, shortDay } from './TreasuryChart'

const DAYS = [
  { date: '2026-10-13', balanceCents: 20000, cumulativeInflowCents: 0, cumulativeOutflowCents: 0, movements: [] },
  { date: '2026-10-14', balanceCents: -15000, cumulativeInflowCents: 0, cumulativeOutflowCents: 0, movements: [] },
  { date: '2026-10-15', balanceCents: -30000, cumulativeInflowCents: 0, cumulativeOutflowCents: 0, movements: [] },
]

describe('TreasuryChart', () => {
  test('names both series so identity never rests on colour alone', () => {
    render(<TreasuryChart projection={DAYS} limitCents={0} />)

    expect(screen.getByText('Solde projeté')).toBeInTheDocument()
    expect(screen.getAllByText('Découvert').length).toBeGreaterThan(0)
  })

  test('marks the overdraft zone with a label, not only a colour', () => {
    render(<TreasuryChart projection={DAYS} limitCents={0} />)

    expect(screen.getByTestId('treasury-overdraft-zone')).toHaveTextContent('Zone de découvert')
  })

  test('labels the first and last day of the axis', () => {
    render(<TreasuryChart projection={DAYS} limitCents={0} />)

    expect(screen.getByText('13 oct.')).toBeInTheDocument()
    expect(screen.getByText('15 oct.')).toBeInTheDocument()
  })

  test('an allowed overdraft moves the zone down but keeps it drawn', () => {
    render(<TreasuryChart projection={DAYS} limitCents={50000} />)

    expect(screen.getByTestId('treasury-overdraft-zone')).toBeInTheDocument()
  })

  test('hovering a day spells out its balance and says when it is in overdraft', async () => {
    const { container } = render(<TreasuryChart projection={DAYS} limitCents={0} />)
    const hits = container.querySelectorAll('rect[fill="transparent"]')

    fireEvent.mouseEnter(hits[1])

    expect(await screen.findByText(/14 oct\. : solde .*150,00.* — en découvert/)).toBeInTheDocument()
  })

  test('hovering a day also lists its movements, a recurring one flagged', async () => {
    const days = [
      DAYS[0],
      {
        ...DAYS[1],
        movements: [
          { id: 'r1', label: 'Taxe foncière', amountCents: -97700, status: 'expected', accountId: 'a1', accountName: 'Courant', source: 'recurring' as const },
        ],
      },
    ]
    const { container } = render(<TreasuryChart projection={days} limitCents={0} />)

    fireEvent.mouseEnter(container.querySelectorAll('rect[fill="transparent"]')[1])

    expect(await screen.findByText(/14 oct\. : solde .* — .*Taxe foncière \(récurrent\) \(-977,00/)).toBeInTheDocument()
  })

  test('renders without a line when there is nothing to project', () => {
    render(<TreasuryChart projection={[]} limitCents={0} />)

    expect(screen.getByTestId('treasury-chart')).toBeInTheDocument()
  })

  test('shortDay drops the year and the zero padding', () => {
    expect(shortDay('2026-03-05')).toBe('5 mars')
  })
})

describe('TreasuryFlowsChart', () => {
  const FLOWS = [
    { date: '2026-10-13', balanceCents: 20000, cumulativeInflowCents: 0, cumulativeOutflowCents: 0, movements: [] },
    {
      date: '2026-10-14',
      balanceCents: 120000,
      cumulativeInflowCents: 529227,
      cumulativeOutflowCents: 18000,
      movements: [
        { id: 'r2', label: 'Claude', amountCents: -18000, status: 'expected', accountId: 'a1', accountName: 'Courant', source: 'recurring' as const },
      ],
    },
  ]

  test('names both lines, the outflows dashed so colour is not the only cue', () => {
    const { container } = render(<TreasuryFlowsChart projection={FLOWS} />)

    expect(screen.getByText('Entrées cumulées')).toBeInTheDocument()
    expect(screen.getByText('Sorties cumulées')).toBeInTheDocument()
    expect(container.querySelectorAll('polyline')).toHaveLength(2)
    expect(container.querySelectorAll('polyline[stroke-dasharray]')).toHaveLength(1)
  })

  test('hovering a day gives the date, both cumulated amounts and the movements', async () => {
    render(<TreasuryFlowsChart projection={FLOWS} />)

    fireEvent.mouseEnter(screen.getAllByTestId('treasury-flows-day')[1])

    const detail = await screen.findByTestId('treasury-flows-detail')
    expect(detail).toHaveTextContent('14 oct.')
    expect(detail).toHaveTextContent(/5\s292,27/)
    expect(detail).toHaveTextContent('Claude (récurrent)')
  })

  test('renders without a line when there is nothing to project', () => {
    render(<TreasuryFlowsChart projection={[]} />)

    expect(screen.getByTestId('treasury-flows-chart')).toBeInTheDocument()
  })
})
