import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ReferenceShareLine } from './ReferenceShareLine'
import type { MonthlyReview } from './useMonthlyReview'

const review = (overrides: Partial<MonthlyReview>): MonthlyReview => ({
  year: 2026,
  month: 9,
  reviewableCents: 0,
  keptCents: 0,
  avoidableCents: 0,
  unratedCents: 0,
  ratedCount: 0,
  pendingCount: 0,
  optimisationScore: null,
  isComplete: false,
  pending: [],
  comparison: {
    thisMonthCents: 247500,
    previousMonthCents: 0,
    recentAverageCents: 0,
    sameMonthLastYearCents: 0,
  },
  referenceSalary: { monthlyCents: 495000, source: 'measured' },
  consumedVsReference: { consumedCents: 247500, differenceCents: -247500, percentOfReference: 50 },
  ...overrides,
})

describe('ReferenceShareLine', () => {
  test('shows the share of the reference the month consumed, and its source', () => {
    render(<ReferenceShareLine review={review({})} />)

    expect(screen.getByTestId('reference-share')).toHaveTextContent(
      /50 % du salaire de référence consommés \(4\s950,00\s€, mesuré sur 12 mois\)/,
    )
  })

  test('shows an over-consumed month beyond 100 %', () => {
    render(
      <ReferenceShareLine
        review={review({
          consumedVsReference: { consumedCents: 594000, differenceCents: 99000, percentOfReference: 120 },
        })}
      />,
    )

    expect(screen.getByTestId('reference-share')).toHaveTextContent(/120 %/)
  })

  test('shows nothing when the reference is unknown', () => {
    const { container } = render(
      <ReferenceShareLine
        review={review({
          referenceSalary: { monthlyCents: null, source: 'unknown' },
          consumedVsReference: { consumedCents: 247500, differenceCents: null, percentOfReference: null },
        })}
      />,
    )

    expect(container).toBeEmptyDOMElement()
  })
})
