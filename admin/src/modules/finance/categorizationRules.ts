export const MATCH_TYPE_CHOICES = [
  { id: 'contains', name: 'Contient' },
  { id: 'starts_with', name: 'Commence par' },
  { id: 'equals', name: 'Égal à' },
]

export const MATCH_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  MATCH_TYPE_CHOICES.map((c) => [c.id, c.name]),
)

export const DIRECTION_CHOICES = [
  { id: 'any', name: 'Peu importe' },
  { id: 'debit', name: 'Dépense' },
  { id: 'credit', name: 'Revenu' },
]

export const DIRECTION_LABELS: Record<string, string> = Object.fromEntries(
  DIRECTION_CHOICES.map((c) => [c.id, c.name]),
)

export const CATEGORY_SOURCE_LABELS: Record<string, string> = {
  none: 'Non catégorisée',
  manual: 'Manuelle',
  rule: 'Par règle',
}

/**
 * Human summary of what a rule narrows on, beyond the label.
 * Amount bounds are absolute cents, as the API stores them.
 */
export function describeRuleScope(
  direction: string,
  minAmountCents?: number | null,
  maxAmountCents?: number | null,
): string {
  const parts: string[] = []

  if (direction && direction !== 'any') {
    parts.push(DIRECTION_LABELS[direction] ?? direction)
  }

  const euros = (cents: number) => (cents / 100).toFixed(2).replace('.', ',') + ' €'

  if (minAmountCents != null && maxAmountCents != null) {
    parts.push(`entre ${euros(minAmountCents)} et ${euros(maxAmountCents)}`)
  } else if (minAmountCents != null) {
    parts.push(`à partir de ${euros(minAmountCents)}`)
  } else if (maxAmountCents != null) {
    parts.push(`jusqu'à ${euros(maxAmountCents)}`)
  }

  return parts.join(' · ')
}

/** €-facing number input bound to an integer-cents field. */
export const centsInput = {
  format: (v?: number) => (v == null ? null : v / 100),
  parse: (v?: number) => (v == null || Number.isNaN(v) ? null : Math.round(v * 100)),
}

/** React-admin hands back resources as IRIs; the API wants the bare id. */
export const idOf = (iri: string) => iri.split('/').pop() ?? iri
