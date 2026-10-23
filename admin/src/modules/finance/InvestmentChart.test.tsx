import { describe, expect, test } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithTheme } from '../../test/renderWithTheme'
import { InvestmentChart } from './InvestmentChart'

const OWED = [100_000, 90_000, 80_000, 70_000]

const points = (testId: string) =>
  (screen.getByTestId(testId).getAttribute('points') ?? '').split(' ').map((pair) => pair.split(',').map(Number) as [number, number])

describe('InvestmentChart', () => {
  test('draws the capital still owed and the rents received, month by month', () => {
    renderWithTheme(<InvestmentChart remainingCents={OWED} monthlyRentCents={30_000} />)

    expect(points('investment-chart-owed')).toHaveLength(4)
    expect(points('investment-chart-rent')).toHaveLength(4)
    expect(screen.getByRole('img')).toHaveAccessibleName(/sur 4 mois/)
    expect(screen.getByRole('img')).toHaveAccessibleName(/700,00\s€ restent à rembourser, 1\s200,00\s€ de loyers perçus/)
    expect(screen.getByText('mois 1')).toBeInTheDocument()
    expect(screen.getByText('mois 4')).toBeInTheDocument()
  })

  test('the owed line goes down and the rent line goes up, on the same scale', () => {
    renderWithTheme(<InvestmentChart remainingCents={OWED} monthlyRentCents={30_000} />)

    const owed = points('investment-chart-owed')
    const rent = points('investment-chart-rent')
    expect(owed[3][1]).toBeGreaterThan(owed[0][1])
    expect(rent[3][1]).toBeLessThan(rent[0][1])
  })

  test('stops at the months held when the resale is dated', () => {
    renderWithTheme(<InvestmentChart remainingCents={OWED} monthlyRentCents={30_000} heldMonths={2} />)

    expect(points('investment-chart-owed')).toHaveLength(2)
    expect(screen.getByText('mois 2')).toBeInTheDocument()
  })

  test('draws nothing without a schedule', () => {
    renderWithTheme(<InvestmentChart remainingCents={[]} monthlyRentCents={30_000} />)

    expect(screen.queryByTestId('investment-chart')).toBeNull()
  })
})
