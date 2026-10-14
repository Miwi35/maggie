import { describe, test, expect } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TreasuryChart, shortDay } from './TreasuryChart'

const DAYS = [
  { date: '2026-10-13', balanceCents: 20000, movements: [] },
  { date: '2026-10-14', balanceCents: -15000, movements: [] },
  { date: '2026-10-15', balanceCents: -30000, movements: [] },
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

  test('renders without a line when there is nothing to project', () => {
    render(<TreasuryChart projection={[]} limitCents={0} />)

    expect(screen.getByTestId('treasury-chart')).toBeInTheDocument()
  })

  test('shortDay drops the year and the zero padding', () => {
    expect(shortDay('2026-03-05')).toBe('5 mars')
  })
})
