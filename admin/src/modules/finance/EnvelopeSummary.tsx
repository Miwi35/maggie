import Alert from '@mui/material/Alert'
import { useGetOne } from 'react-admin'
import { formatCents } from './accountTypes'
import { formatPeriod } from './budgetModes'

interface EnvelopeSummaryProps {
  category?: string
  mode?: string
  year?: number
  month?: number | null
  amountCents?: number
  currency?: string
}

/**
 * Restates the form in one sentence, so there is no doubt about what the
 * period fields actually budget.
 */
export const EnvelopeSummary = ({
  category,
  mode = 'monthly',
  year,
  month,
  amountCents,
  currency = 'EUR',
}: EnvelopeSummaryProps) => {
  const { data: categoryRecord } = useGetOne(
    'categories',
    { id: category as string },
    { enabled: Boolean(category) },
  )

  if (!category || !year || amountCents == null) {
    return (
      <Alert severity="info" variant="outlined" sx={{ width: '100%' }}>
        Choisissez une catégorie, un montant et une période pour voir le récapitulatif.
      </Alert>
    )
  }

  const categoryName = (categoryRecord?.name as string) ?? 'cette catégorie'
  const period = formatPeriod(mode, year, month)
  const pace =
    mode === 'annual'
      ? "L'enveloppe couvre les douze mois de l'année."
      : 'Le budget repart à zéro le mois suivant.'

  return (
    <Alert severity="success" variant="outlined" sx={{ width: '100%' }}>
      <strong>{formatCents(amountCents, currency)}</strong> pour <strong>{categoryName}</strong> —{' '}
      {period}. {pace}
    </Alert>
  )
}
