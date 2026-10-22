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
})
