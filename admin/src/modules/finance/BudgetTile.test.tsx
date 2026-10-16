import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { BudgetTile, dayLabel } from './BudgetTile'
import { dashboardFixture } from './dashboardFixture'
import type { BudgetTile as BudgetTileData } from './useFinanceDashboard'

const renderTile = (change: (tile: BudgetTileData) => void = () => undefined) => {
  const tile = dashboardFixture().tiles.budget
  change(tile)

  return render(
    <MemoryRouter>
      <BudgetTile tile={tile} />
    </MemoryRouter>,
  )
}

describe('BudgetTile', () => {
  test('draws a bar per envelope, red when overspent and amber when overcommitted', () => {
    renderTile()

    const bars = screen.getAllByTestId('budget-bar')
    expect(bars.map((bar) => [bar.getAttribute('data-category'), bar.getAttribute('data-state')])).toEqual([
      ['Courses', 'ok'],
      ['Loisirs', 'overspent'],
      ['Sorties', 'overcommitted'],
    ])
    expect(bars[0]).toHaveTextContent(/250,00\s€ \/ 400,00\s€/)
  })

  test('says there is no envelope when the month has none', () => {
    renderTile((tile) => {
      tile.envelopes = []
    })

    expect(screen.getByText('Aucune enveloppe sur ce mois.')).toBeInTheDocument()
  })

  test('shows the expected salary with its pay day, and the balance expected before it', () => {
    renderTile()

    const salary = screen.getByTestId('expected-salary')
    expect(salary).toHaveTextContent(/3\s000,00\s€/)
    expect(salary).toHaveTextContent('· le 28 octobre')
    expect(salary).toHaveTextContent('attendu (opération récurrente)')

    const balance = screen.getByTestId('balance-before-salary')
    expect(balance).toHaveTextContent(/910,00\s€/)
    expect(balance).toHaveTextContent('· le 28 octobre')
  })

  test('leaves the date out when the salary has no pay day', () => {
    renderTile((tile) => {
      tile.expectedSalary = { monthlyCents: 300000, source: 'declared', expectedOn: null }
    })

    expect(screen.getByTestId('expected-salary')).not.toHaveTextContent('· le')
  })

  test('says « salaire non déclaré » and « solde attendu inconnu », never 0 €, when nothing is known', () => {
    renderTile((tile) => {
      tile.expectedSalary = null
      tile.balanceBeforeSalary = null
    })

    const tile = screen.getByTestId('tile-budget')
    expect(screen.getByText('salaire non déclaré')).toBeInTheDocument()
    expect(screen.getByText('solde attendu inconnu')).toBeInTheDocument()
    expect(screen.getByTestId('expected-salary')).not.toHaveTextContent(/0,00\s€/)
    expect(screen.getByTestId('balance-before-salary')).not.toHaveTextContent(/0,00\s€/)
    expect(screen.getByRole('link', { name: /Ajouter l'opération récurrente « Salaire »/ })).toHaveAttribute(
      'href',
      '/finance/recurring-operations',
    )
    expect(tile).toBeInTheDocument()
  })

  test('flags a negative expected balance', () => {
    renderTile((tile) => {
      tile.balanceBeforeSalary = { onDate: '2026-10-28', balanceCents: -5000 }
    })

    expect(screen.getByTestId('balance-before-salary')).toHaveTextContent(/-50,00\s€/)
  })

  test.each([
    [{ state: 'complete', isConfigured: true, coveragePercent: 100 }, 'Matelas complet'],
    [{ state: 'building', isConfigured: true, coveragePercent: 70 }, 'Matelas à 70 %'],
    [{ state: 'building', isConfigured: false, coveragePercent: 0 }, 'Matelas non configuré'],
  ] as const)('writes the matelas as %j', (cushion, text) => {
    renderTile((tile) => {
      tile.cushion = { ...tile.cushion, ...cushion }
    })

    expect(screen.getByTestId('cushion-status')).toHaveTextContent(text)
  })

  test('links to the budgets', () => {
    renderTile()

    expect(screen.getByRole('link', { name: 'Budget' })).toHaveAttribute('href', '/envelopes')
  })
})

describe('dayLabel', () => {
  test('reads an ISO date as the same calendar day', () => {
    expect(dayLabel('2026-01-01')).toBe('1 janvier')
    expect(dayLabel('2026-12-31')).toBe('31 décembre')
  })
})
