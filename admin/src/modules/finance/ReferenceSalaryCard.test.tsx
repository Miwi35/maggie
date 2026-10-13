import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ReferenceSalaryCard } from './ReferenceSalaryCard'
import type { ReferenceSalary } from './referenceSalary'

const renderCard = (reference: ReferenceSalary) =>
  render(
    <MemoryRouter>
      <ReferenceSalaryCard reference={reference} />
    </MemoryRouter>,
  )

describe('ReferenceSalaryCard', () => {
  test('shows a declared salary and says where it was declared', () => {
    renderCard({ monthlyCents: 300000, source: 'declared' })

    expect(screen.getByText(/3\s000,00\s€/)).toBeInTheDocument()
    expect(screen.getByText('déclaré sur le matelas')).toBeInTheDocument()
  })

  test('shows an expected salary as coming from the recurring operation', () => {
    renderCard({ monthlyCents: 495000, source: 'expected' })

    expect(screen.getByText(/4\s950,00\s€/)).toBeInTheDocument()
    expect(screen.getByText('attendu (opération récurrente)')).toBeInTheDocument()
  })

  test('shows a measured salary as measured over twelve months', () => {
    renderCard({ monthlyCents: 495000, source: 'measured' })

    expect(screen.getByText(/4\s950,00\s€/)).toBeInTheDocument()
    expect(screen.getByText('mesuré sur 12 mois')).toBeInTheDocument()
  })

  test('says « non renseigné » with a way to fix it, and never 0 €', () => {
    renderCard({ monthlyCents: null, source: 'unknown' })

    expect(screen.getByText('non renseigné')).toBeInTheDocument()
    expect(screen.queryByText(/0,00\s€/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /opération récurrente « Salaire »/ })).toHaveAttribute(
      'href',
      '/finance/recurring-operations',
    )
  })

  test('never shows 0 € even if the API sent a zero with a source', () => {
    renderCard({ monthlyCents: 0, source: 'expected' })

    expect(screen.getByText('non renseigné')).toBeInTheDocument()
    expect(screen.queryByText(/0,00\s€/)).not.toBeInTheDocument()
  })
})
