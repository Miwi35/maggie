import { ruleIdentifies } from './categorizationRules'
import { PREVIEW_DELAY_MS, usePostPreview } from './usePostPreview'
import type { PreviewState } from './usePostPreview'

export { PREVIEW_DELAY_MS }

export interface RulePreviewCriteria {
  labelPattern: string
  matchType: string
  direction: string
  minAmountCents: number | null
  maxAmountCents: number | null
  /** Bare id; null while no category is chosen. */
  categoryId: string | null
  priority: number
  isActive: boolean
  /** Bare id of the rule being edited; null on creation. */
  ruleId: string | null
}

export interface RulePreviewMatch {
  transactionId: string
  label: string
  amountCents: number
  bookedAt: string
  currentCategoryId: string | null
  wouldChange: boolean
}

export interface RulePreviewResult {
  total: number
  changeCount: number
  matches: RulePreviewMatch[]
}

export type RulePreviewState = PreviewState<RulePreviewResult>

/**
 * The transactions a rule would catch, refreshed as its criteria are typed.
 * Nothing is asked until the rule has an identity: a label, or a direction
 * with an amount bound.
 */
export function useCategorizationRulePreview(
  criteria: RulePreviewCriteria,
  delayMs = PREVIEW_DELAY_MS,
): RulePreviewState {
  const identified = ruleIdentifies(criteria.labelPattern, criteria.direction, criteria.minAmountCents, criteria.maxAmountCents)

  return usePostPreview<RulePreviewResult>('/api/finance/categorization-rules/preview', identified ? criteria : null, delayMs)
}
