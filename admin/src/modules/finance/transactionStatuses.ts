/**
 * A transaction is an expense or an income, and the sign of its amount says
 * which. The status enum is shared (`spent`, `committed`, `planned`,
 * `to_arbitrate`) but its wording is not: a salary is never "Dépensée".
 */
export type TransactionNature = 'expense' | 'income'

export interface StatusChoice {
  id: string
  name: string
}

export const EXPENSE_STATUS_CHOICES: StatusChoice[] = [
  { id: 'spent', name: 'Dépensée' },
  { id: 'committed', name: 'Engagée' },
  { id: 'planned', name: 'Planifiée' },
  { id: 'to_arbitrate', name: 'À arbitrer' },
]

/** "Engagée" means nothing for money coming in: it is not offered. */
export const INCOME_STATUS_CHOICES: StatusChoice[] = [
  { id: 'spent', name: 'Reçue' },
  { id: 'planned', name: 'Attendue' },
  { id: 'to_arbitrate', name: 'À arbitrer' },
]

export const STATUS_CHOICES: Record<TransactionNature, StatusChoice[]> = {
  expense: EXPENSE_STATUS_CHOICES,
  income: INCOME_STATUS_CHOICES,
}

export const STATUS_HELP: Record<TransactionNature, string> = {
  expense:
    'Dépensée et engagée sont déduites du budget ; planifiée est mise de côté ; à arbitrer ne compte pas encore.',
  income:
    'Reçue est déjà sur le compte et compte dans vos revenus ; attendue est prévue mais pas encore arrivée ; à arbitrer ne compte pas encore.',
}

export const STATUS_SECTION_TITLE: Record<TransactionNature, string> = {
  expense: 'État de la dépense',
  income: 'État de la recette',
}

export const EXCEPTIONAL_LABEL: Record<TransactionNature, string> = {
  expense: 'Dépense exceptionnelle',
  income: 'Recette exceptionnelle',
}

export const EXCEPTIONAL_HELP: Record<TransactionNature, string> = {
  expense: 'À cocher pour ce qui ne se reproduira pas — un déménagement, une réparation.',
  income: 'À cocher pour ce qui ne se reproduira pas — une prime, un remboursement.',
}

/** The sign decides: zero has no direction, and reads as an expense like before. */
export const natureOfAmount = (amountCents: number | null | undefined): TransactionNature =>
  typeof amountCents === 'number' && amountCents > 0 ? 'income' : 'expense'

/** The amount is typed without a sign; the nature gives it back. */
export const signedAmountCents = (
  amountCents: number | null | undefined,
  nature: TransactionNature,
): number => {
  const magnitude = Math.abs(amountCents ?? 0)
  return nature === 'income' ? magnitude : -magnitude
}

/** The label of a status as it reads for this transaction, by the sign of its amount. */
export const statusLabel = (status: string, amountCents: number | null | undefined): string => {
  const nature = natureOfAmount(amountCents)
  // A received-or-expected income cannot be "committed": the few that exist
  // from before the form knew better read as expected.
  const effective = nature === 'income' && status === 'committed' ? 'planned' : status
  return STATUS_CHOICES[nature].find((c) => c.id === effective)?.name ?? status
}
