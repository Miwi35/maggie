import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { REFERENCE_SALARY_SOURCE_LABELS } from './referenceSalary'
import type { MonthlyReview } from './useMonthlyReview'

/** The share of the reference salary the month consumed; nothing when there is no mark. */
export const ReferenceShareLine = ({ review }: { review: MonthlyReview }) => {
  const { percentOfReference } = review.consumedVsReference
  const { monthlyCents, source } = review.referenceSalary

  if (percentOfReference === null || monthlyCents === null) {
    return null
  }

  return (
    <Typography variant="body2" sx={{ mt: 1 }} data-testid="reference-share">
      {percentOfReference} % du salaire de référence consommés ({formatCents(monthlyCents)},{' '}
      {REFERENCE_SALARY_SOURCE_LABELS[source]})
    </Typography>
  )
}
