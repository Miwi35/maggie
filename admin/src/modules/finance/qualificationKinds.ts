/** What an operation or a transaction says about itself beyond its category (MAG-476). */
export type QualificationKind = 'none' | 'saving' | 'investment' | 'loan' | 'repayment'

export interface QualificationValue {
  mandatory: boolean
  kind: QualificationKind
}

export const NO_QUALIFICATION: QualificationValue = { mandatory: false, kind: 'none' }

export const KIND_CHOICES: { id: QualificationKind; name: string }[] = [
  { id: 'none', name: 'Aucun' },
  { id: 'saving', name: 'Épargne' },
  { id: 'investment', name: 'Investissement' },
  { id: 'loan', name: 'Prêt' },
  { id: 'repayment', name: 'Remboursement' },
]

/** The direction is never part of the qualification: it comes from the sign of the amount. */
export type Sense = 'out' | 'in' | 'unknown'

export const senseOfAmount = (amountCents: number | null | undefined): Sense =>
  amountCents == null || amountCents === 0 ? 'unknown' : amountCents < 0 ? 'out' : 'in'

export const kindHelp = (kind: QualificationKind, sense: Sense): string => {
  switch (kind) {
    case 'repayment':
      return sense === 'out'
        ? 'Remboursement : ici, je rembourse (le montant sort du compte).'
        : sense === 'in'
          ? 'Remboursement : ici, on me rembourse (le montant entre sur le compte).'
          : 'Remboursement : « je rembourse » ou « on me rembourse » selon le signe du montant.'
    case 'loan':
      return sense === 'in'
        ? 'Prêt : ici, on m’accorde un prêt (le montant entre sur le compte).'
        : sense === 'out'
          ? 'Prêt : ici, je prête (le montant sort du compte).'
          : 'Prêt : le sens vient du montant — un prêt qui entre, un prêt accordé qui sort.'
    case 'saving':
      return 'Épargne : le sens vient du montant — je mets de côté (sort) ou je reprends (entre).'
    case 'investment':
      return 'Investissement : le sens vient du montant — j’investis (sort) ou je désinvestis (entre).'
    default:
      return 'Le sens (dépense ou recette) vient du montant, jamais du type.'
  }
}

/** « posée par la série Crédit auto », « à la main »: where the qualification of a line comes from. */
export const provenanceSentence = (
  qualifiedBy: string | null | undefined,
  seriesLabel?: string | null,
  ruleLabel?: string | null,
): string | null => {
  switch (qualifiedBy) {
    case 'manual':
      return 'à la main'
    case 'series':
      return seriesLabel ? `posée par la série ${seriesLabel}` : 'posée par une série'
    case 'rule':
      return ruleLabel ? `posée par la règle ${ruleLabel}` : 'posée par une règle'
    default:
      return null
  }
}
