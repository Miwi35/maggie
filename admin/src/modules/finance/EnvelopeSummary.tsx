import Alert from '@mui/material/Alert'
import { useGetOne } from 'react-admin'
import { formatCents } from './accountTypes'
import { ONE_OFF, formatPeriod } from './budgetModes'
import { categoryLabel } from './categoryTree'

interface EnvelopeSummaryProps {
  category?: string
  mode?: string
  /** What a one-off envelope is for. */
  name?: string
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
  name,
  year,
  month,
  amountCents,
  currency = 'EUR',
}: EnvelopeSummaryProps) => {
  const oneOff = mode === ONE_OFF
  const { data: categoryRecord } = useGetOne(
    'categories',
    { id: category as string },
    { enabled: Boolean(category) && !oneOff },
  )

  if (oneOff) {
    if (!name || amountCents == null) {
      return (
        <Alert severity="info" variant="outlined" sx={{ width: '100%' }}>
          Donnez un nom et un montant à l'événement pour voir le récapitulatif.
        </Alert>
      )
    }

    return (
      <Alert severity="success" variant="outlined" sx={{ width: '100%' }}>
        <strong>{formatCents(amountCents, currency)}</strong> pour <strong>{name}</strong>, hors budget mensuel.
        Attribuez-y les dépenses depuis la fiche de chaque transaction.
      </Alert>
    )
  }

  if (!category || !year || amountCents == null) {
    return (
      <Alert severity="info" variant="outlined" sx={{ width: '100%' }}>
        Choisissez une catégorie, un montant et une période pour voir le récapitulatif.
      </Alert>
    )
  }

  const categoryName = categoryLabel(categoryRecord) || 'cette catégorie'
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
