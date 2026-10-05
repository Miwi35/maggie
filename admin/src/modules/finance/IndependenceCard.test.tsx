import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { IndependenceCard } from './IndependenceCard'
import type { IndependenceCounter } from './useFinanceDashboard'

const counter = (overrides: Partial<IndependenceCounter> = {}): IndependenceCounter => ({
  coveragePercent: 80,
  lifestyleCents: 10000,
  passiveIncomeCents: 8000,
  gapCents: 2000,
  sampleMonths: 3,
  isMeasurable: true,
  hasPassiveIncomeCategories: true,
  isReached: false,
  loanPaymentsCents: 20000,
  monthlyNeedCents: 30000,
  coverageWithDebtPercent: 27,
  nextMilestonePercent: 100,
  nextMilestoneGapCents: 2000,
  milestones: [{ percent: 100, isReached: false, monthlyIncomeNeededCents: 10000 }],
  byCategory: [
    { categoryId: '1', categoryName: 'Loyers perçus', monthlyCents: 6000, sharePercent: 75 },
    { categoryId: '2', categoryName: 'Dividendes', monthlyCents: 2000, sharePercent: 25 },
  ],
  ...overrides,
})

describe('IndependenceCard', () => {
  test('shows the coverage and both terms of the ratio', () => {
    render(<IndependenceCard counter={counter()} />)

    expect(screen.getByText('80 %')).toBeInTheDocument()
    expect(screen.getByText(/80,00 € de rentes sur 100,00 € de train de vie/)).toBeInTheDocument()
    expect(screen.getByText(/mesurés sur 3 mois/)).toBeInTheDocument()
  })

  test('says what is missing and which milestone comes next', () => {
    render(<IndependenceCard counter={counter()} />)

    expect(screen.getByText(/Il manque 20,00 € par mois/)).toBeInTheDocument()
    expect(screen.getByText(/prochain palier 100 %/)).toBeInTheDocument()
  })

  test('breaks the rentes down by category', () => {
    render(<IndependenceCard counter={counter()} />)

    const rentes = screen.getAllByTestId('rente')
    expect(rentes.map((r) => r.getAttribute('data-category'))).toEqual([
      'Loyers perçus',
      'Dividendes',
    ])
    expect(rentes[0]).toHaveTextContent('60,00 € / mois')
    expect(rentes[0]).toHaveTextContent('75 %')
  })

  test('reports the ratio against the month including loan payments', () => {
    render(<IndependenceCard counter={counter()} />)

    expect(screen.getByText(/Mensualités de prêt comprises : 27 %/)).toBeInTheDocument()
  })

  test('leaves the loan line out when nothing is being repaid', () => {
    render(<IndependenceCard counter={counter({ loanPaymentsCents: 0 })} />)

    expect(screen.queryByText(/Mensualités de prêt comprises/)).not.toBeInTheDocument()
  })

  /** Above 100 % the rentes cover more than the month costs. */
  test('says the rentes cover the train de vie once they do', () => {
    render(
      <IndependenceCard
        counter={counter({
          coveragePercent: 200,
          passiveIncomeCents: 20000,
          gapCents: 0,
          isReached: true,
          nextMilestonePercent: null,
          nextMilestoneGapCents: null,
        })}
      />,
    )

    expect(screen.getByText('200 %')).toBeInTheDocument()
    expect(screen.getByText(/Les rentes couvrent le train de vie/)).toBeInTheDocument()
    expect(screen.queryByText(/Il manque/)).not.toBeInTheDocument()
  })

  test('sends the user to declare a rente when none is', () => {
    render(<IndependenceCard counter={counter({ hasPassiveIncomeCategories: false })} />)

    expect(screen.getByText(/Aucune catégorie n'est déclarée comme rente/)).toBeInTheDocument()
    expect(screen.queryByText('80 %')).not.toBeInTheDocument()
  })

  /** No denominator, no percentage — and 0 % would be a verdict. */
  test('says nothing is measured yet rather than showing zero percent', () => {
    render(
      <IndependenceCard
        counter={counter({
          isMeasurable: false,
          coveragePercent: 0,
          lifestyleCents: 0,
          passiveIncomeCents: 0,
        })}
      />,
    )

    expect(screen.getByText(/Pas encore de train de vie mesuré/)).toBeInTheDocument()
    expect(screen.queryByText('0 %')).not.toBeInTheDocument()
  })
})
