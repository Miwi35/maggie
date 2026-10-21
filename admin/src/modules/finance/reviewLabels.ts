import type { SortResult } from './useReviewQueue'

export const TO_QUALIFY_LABEL = 'À qualifier'
export const RECOGNISED_LABEL = 'Reconnue'
export const CONTROLLED_LABEL = 'Contrôlée'

const plural = (n: number, one: string, many: string) => (n > 1 ? many : one)

/** What the rule that came with an answer did, said in one line; null when no rule was involved. */
export const ruleNotice = (result: SortResult | null): string | null => {
  if (!result?.rule) return null
  const head = result.ruleCreated ? 'Règle créée' : 'Règle appliquée'
  if (result.alsoSorted < 1) return head

  return `${head} · ${result.alsoSorted} ${plural(result.alsoSorted, 'autre ligne classée', 'autres lignes classées')}`
}
