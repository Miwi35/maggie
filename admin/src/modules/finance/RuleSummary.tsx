import Alert from '@mui/material/Alert'
import { useGetOne } from 'react-admin'
import { MATCH_TYPE_LABELS, describeRuleScope, ruleIdentifies } from './categorizationRules'
import { categoryLabel } from './categoryTree'

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

  const identified = ruleIdentifies(labelPattern, direction, minAmountCents, maxAmountCents)

  if (!identified || !category) {
    return (
      <Alert severity="info" variant="outlined" sx={{ width: '100%' }}>
        Renseignez un texte à reconnaître (ou un sens et un montant) et une catégorie pour voir le récapitulatif.
      </Alert>
    )
  }

  const comparison = (MATCH_TYPE_LABELS[matchType] ?? matchType).toLowerCase()
  const categoryName = categoryLabel(categoryRecord) || 'cette catégorie'
  const scope = describeRuleScope(direction, minAmountCents, maxAmountCents)

  return (
    <Alert severity="success" variant="outlined" sx={{ width: '100%' }}>
      {labelPattern?.trim() ? (
        <>
          Une transaction dont le libellé {comparison} « <strong>{labelPattern}</strong> »
          {scope && <> ({scope})</>}
        </>
      ) : (
        <>Toute transaction ({scope})</>
      )}{' '}
      sera classée dans <strong>{categoryName}</strong>.
    </Alert>
  )
}
