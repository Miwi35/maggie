import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router-dom'
import { formatCents } from './accountTypes'
import {
  RECURRING_OPERATIONS_PATH,
  REFERENCE_SALARY_SOURCE_LABELS,
  isReferenceKnown,
} from './referenceSalary'
import type { ReferenceSalary } from './referenceSalary'

/**
 * The salary the month is judged on (MAG-275), and where the figure comes from.
 *
 * Unknown is said as « non renseigné » with the way to fix it, never as 0 €:
 * a zero would read as a verdict on a salary nobody has entered.
 */
export const ReferenceSalaryCard = ({ reference }: { reference: ReferenceSalary }) => (
  <Card sx={{ flex: '1 1 320px' }} data-testid="reference-salary-card">
    <CardContent>
      <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
        Salaire de référence
      </Typography>
      {isReferenceKnown(reference) ? (
        <>
          <Typography variant="h5" sx={{ my: 0.5 }}>
            {formatCents(reference.monthlyCents ?? 0)}
            <Typography component="span" variant="body2" color="text.secondary">
              {' '}
              / mois
            </Typography>
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {REFERENCE_SALARY_SOURCE_LABELS[reference.source]}
          </Typography>
        </>
      ) : (
        <>
          <Typography variant="h6" color="text.secondary" sx={{ my: 0.5 }}>
            {REFERENCE_SALARY_SOURCE_LABELS.unknown}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Le mois est jugé sur ce repère.{' '}
            <Link component={RouterLink} to={RECURRING_OPERATIONS_PATH}>
              Ajouter l'opération récurrente « Salaire »
            </Link>
          </Typography>
        </>
      )}
    </CardContent>
  </Card>
)
