import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { nextProjectFixture } from './dashboardFixture'
import { NextProjectCard } from './NextProjectCard'

describe('NextProjectCard', () => {
  test('shows the project: name, gathered over amount, progress and when it falls', () => {
    render(<NextProjectCard project={nextProjectFixture()} />)

    const card = screen.getByTestId('next-project-card')
    expect(card).toHaveTextContent('Prochain projet')
    expect(card).toHaveTextContent('Vélo')
    expect(card).toHaveTextContent(/740,00\s€ sur 1\s200,00\s€/)
    expect(screen.getByTestId('next-project-date')).toHaveTextContent(/^dans 4 mois · /)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '62')
  })

  test('says « ce mois-ci » when the purchase falls this month', () => {
    const now = new Date()
    const thisMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
    render(<NextProjectCard project={nextProjectFixture({ purchaseMonth: thisMonth })} />)

    expect(screen.getByTestId('next-project-date')).toHaveTextContent(/^ce mois-ci · /)
  })

  test('lists the following projects as pills', () => {
    render(<NextProjectCard project={nextProjectFixture()} />)

    const following = screen.getAllByTestId('next-project-following')
    expect(following).toHaveLength(1)
    expect(following[0]).toHaveTextContent(/Voyage au Japon · 3\s000,00\s€/)
  })

  test('states the delay the overspending causes, with the envelopes and the amount', () => {
    render(<NextProjectCard project={nextProjectFixture()} />)

    const negative = screen.getByTestId('next-project-negative')
    expect(negative).toHaveTextContent(/Les dépassements de ce mois \(Restaurants et Obsydian, \+58,00\s€\) repoussent Voyage au Japon d'un mois\./)
  })

  test('says the number of months when more than one, and joins three envelopes', () => {
    const base = nextProjectFixture()
    const delay = { ...base.delay!, months: 3, envelopes: ['A', 'B', 'C'] }
    render(<NextProjectCard project={{ ...base, delay }} />)

    expect(screen.getByTestId('next-project-negative')).toHaveTextContent(/\(A, B et C, .*repoussent Voyage au Japon de 3 mois\./)
  })

  test('shows no negative return without a delay', () => {
    render(<NextProjectCard project={nextProjectFixture({ delay: null })} />)

    expect(screen.queryByTestId('next-project-negative')).not.toBeInTheDocument()
  })

  test('shows no pill without a following project, and is not clickable', () => {
    render(<NextProjectCard project={nextProjectFixture({ following: [], delay: null })} />)

    expect(screen.queryByTestId('next-project-following')).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  test('names the project beyond the horizon, in years and months, with no following pill nor delay', () => {
    const beyond = { projectId: 'p9', name: 'Salle de bain', amountCents: 900000, gatheredCents: 0, horizonMonths: 36 }
    render(<NextProjectCard project={beyond} />)

    const card = screen.getByTestId('next-project-card')
    expect(card).toHaveTextContent('Prochain projet')
    expect(card).toHaveTextContent('Salle de bain')
    expect(card).toHaveTextContent(/0,00\s€ sur 9\s000,00\s€/)
    expect(screen.getByTestId('next-project-date')).toHaveTextContent('au-delà de 3 ans')
    expect(card).not.toHaveTextContent('36 mois')
    expect(screen.queryByTestId('next-project-following')).not.toBeInTheDocument()
    expect(screen.queryByTestId('next-project-negative')).not.toBeInTheDocument()
  })

  test('says the wait in years and months for a project that falls in more than a year', () => {
    const now = new Date()
    const purchase = new Date(now.getFullYear(), now.getMonth() + 16, 1)
    const month = `${purchase.getFullYear()}-${String(purchase.getMonth() + 1).padStart(2, '0')}`
    render(<NextProjectCard project={nextProjectFixture({ purchaseMonth: month })} />)

    expect(screen.getByTestId('next-project-date')).toHaveTextContent(/^dans 1 an et 4 mois · /)
  })
})
