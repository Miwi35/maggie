import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { NextProjectCard } from './NextProjectCard'

describe('NextProjectCard', () => {
  test('shows the example project: title, amounts, progress and delay', () => {
    render(<NextProjectCard />)

    const card = screen.getByTestId('next-project-card')
    expect(card).toHaveTextContent('Prochain projet')
    expect(card).toHaveTextContent('Vélo électrique')
    expect(card).toHaveTextContent(/740,00\s€ sur 1\s200,00\s€/)
    expect(card).toHaveTextContent('dans 4 mois')
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '62')
  })

  test('lists the following projects and one negative return', () => {
    render(<NextProjectCard />)

    expect(screen.getAllByTestId('next-project-following')).toHaveLength(2)
    expect(screen.getByTestId('next-project-negative')).toHaveTextContent(/-460,00\s€/)
  })

  test('is neither clickable nor a placeholder text', () => {
    render(<NextProjectCard />)

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText(/apparaîtra ici/)).not.toBeInTheDocument()
  })
})
