import Alert from '@mui/material/Alert'
import { useGetOne } from 'react-admin'
import { MATCH_TYPE_LABELS, describeRuleScope } from './categorizationRules'

interface RuleSummaryProps {
  category?: string
  matchType?: string
  labelPattern?: string
  direction?: string
  minAmountCents?: number | null
  maxAmountCents?: number | null
}

/** Restates the rule as the sentence a person would say out loud. */
export const RuleSummary = ({
  category,
  matchType = 'contains',
  labelPattern,
  direction = 'any',
  minAmountCents,
  maxAmountCents,
}: RuleSummaryProps) => {
  const { data: categoryRecord } = useGetOne(
    'categories',
    { id: category as string },
    { enabled: Boolean(category) },
  )

  if (!labelPattern || !category) {
    return (
      <Alert severity="info" variant="outlined" sx={{ width: '100%' }}>
        Renseignez un texte à reconnaître et une catégorie pour voir le récapitulatif.
      </Alert>
    )
  }

  const comparison = (MATCH_TYPE_LABELS[matchType] ?? matchType).toLowerCase()
  const categoryName = (categoryRecord?.name as string) ?? 'cette catégorie'
  const scope = describeRuleScope(direction, minAmountCents, maxAmountCents)

  return (
    <Alert severity="success" variant="outlined" sx={{ width: '100%' }}>
      Une transaction dont le libellé {comparison} « <strong>{labelPattern}</strong> »
      {scope && <> ({scope})</>} sera classée dans <strong>{categoryName}</strong>.
    </Alert>
  )
}
