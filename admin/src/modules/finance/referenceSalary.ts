/** Where the reference salary comes from, as `GetReferenceSalary` names it. */
export type ReferenceSalarySource = 'declared' | 'expected' | 'measured' | 'unknown'

/** The mark the month is judged on; `monthlyCents` is null — never 0 — when nothing is known. */
export interface ReferenceSalary {
  monthlyCents: number | null
  source: ReferenceSalarySource
}

/** What the month consumed against the mark; both figures are null without one. */
export interface ConsumedVsReference {
  consumedCents: number
  differenceCents: number | null
  percentOfReference: number | null
}

export const REFERENCE_SALARY_SOURCE_LABELS: Record<ReferenceSalarySource, string> = {
  declared: 'déclaré sur le matelas',
  expected: 'attendu (opération récurrente)',
  measured: 'mesuré sur 12 mois',
  unknown: 'non renseigné',
}

export const RECURRING_OPERATIONS_PATH = '/finance/recurring-operations'

/** A mark exists only when the API gave an amount: `unknown` is never a 0 € salary. */
export function isReferenceKnown(reference: ReferenceSalary | null | undefined): boolean {
  return null != reference && reference.source !== 'unknown' && reference.monthlyCents != null && reference.monthlyCents > 0
}
