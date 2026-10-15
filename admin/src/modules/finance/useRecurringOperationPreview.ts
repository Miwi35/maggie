import { PREVIEW_DELAY_MS, usePostPreview } from './usePostPreview'
import type { PreviewState } from './usePostPreview'

export interface RecurringPreviewCriteria {
  /** Signed cents: negative is a dépense. Null while no amount is typed. */
  referenceAmountCents: number | null
  amountTolerancePercent: number
  counterpartyName: string
  labelPattern: string
  /** Bare id; empty while no account is chosen. */
  accountId: string
}

export interface RecurringPreviewMatch {
  transactionId: string
  label: string
  amountCents: number
  bookedAt: string
  accountId: string
}

export interface RecurringPreviewResult {
  total: number
  matches: RecurringPreviewMatch[]
}

export type RecurringPreviewState = PreviewState<RecurringPreviewResult>

/**
 * The transactions a series would recognise, refreshed as it is typed. Without
 * an amount there is nothing to ask: the amount and its sign are the series'
 * minimal identity.
 */
export function useRecurringOperationPreview(
  criteria: RecurringPreviewCriteria,
  delayMs = PREVIEW_DELAY_MS,
): RecurringPreviewState {
  const { referenceAmountCents, amountTolerancePercent, counterpartyName, labelPattern, accountId } = criteria
  const askable =
    referenceAmountCents !== null &&
    Number.isInteger(referenceAmountCents) &&
    referenceAmountCents !== 0 &&
    Number.isInteger(amountTolerancePercent) &&
    amountTolerancePercent >= 0 &&
    amountTolerancePercent <= 100

  const body = askable
    ? {
        referenceAmountCents,
        amountTolerancePercent,
        ...(counterpartyName.trim() === '' ? {} : { counterpartyName: counterpartyName.trim() }),
        ...(labelPattern.trim() === '' ? {} : { labelPattern: labelPattern.trim() }),
        ...(accountId === '' ? {} : { accountId }),
      }
    : null

  return usePostPreview<RecurringPreviewResult>('/api/finance/recurring-operations/preview', body, delayMs)
}
