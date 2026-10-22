/** Basis points are how the API stores rates: 350 is 3,50 %. */
export const basisPointsInput = {
  format: (v?: number) => (v == null ? null : v / 100),
  parse: (v?: number) => (v == null || Number.isNaN(v) ? null : Math.round(v * 100)),
}

export const centsInput = {
  format: (v?: number) => (v == null ? null : v / 100),
  parse: (v?: number) => (v == null || Number.isNaN(v) ? null : Math.round(v * 100)),
}

/** "3,50 %" from 350 basis points. */
export function formatRate(basisPoints = 0): string {
  return `${(basisPoints / 100).toFixed(2).replace('.', ',')} %`
}

/** "Mars 2027" from the API's "2027-03". */
const MONTHS = [
  'Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin',
  'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre',
]

export function formatMonth(month?: string | null): string {
  if (!month) {
    return '—'
  }
  const [year, monthNumber] = month.split('-')
  const name = MONTHS[Number(monthNumber) - 1]

  return name ? `${name} ${year}` : month
}

/** How long is left, said the way a person would. */
export function formatRemaining(monthsRemaining?: number | null): string {
  if (monthsRemaining == null) {
    return "Au-delà de l'horizon"
  }
  if (monthsRemaining < 12) {
    return `${monthsRemaining} mois`
  }

  const years = Math.floor(monthsRemaining / 12)
  const months = monthsRemaining % 12

  return months === 0 ? `${years} an${years > 1 ? 's' : ''}` : `${years} an${years > 1 ? 's' : ''} et ${months} mois`
}

export type LoanType =
  | 'amortizing_fixed'
  | 'amortizing_variable'
  | 'in_fine'
  | 'stepped'
  | 'deferred_partial'
  | 'deferred_total'
  | 'bridge'

export type PenaltyMode = 'legal_cap' | 'fixed' | 'exempt' | 'consumer'

/** Same words on the admin, the tablet and the phone. */
export const LOAN_TYPES: { id: LoanType; name: string }[] = [
  { id: 'amortizing_fixed', name: 'Amortissable taux fixe' },
  { id: 'amortizing_variable', name: 'Amortissable taux variable' },
  { id: 'in_fine', name: 'In fine' },
  { id: 'stepped', name: 'À paliers' },
  { id: 'deferred_partial', name: 'Différé partiel' },
  { id: 'deferred_total', name: 'Différé total' },
  { id: 'bridge', name: 'Prêt relais' },
]

export const PENALTY_MODES: { id: PenaltyMode; name: string }[] = [
  { id: 'legal_cap', name: 'Plafond légal' },
  { id: 'fixed', name: 'Montant fixe' },
  { id: 'exempt', name: 'Aucune IRA' },
  { id: 'consumer', name: 'Crédit à la consommation' },
]

export const loanTypeLabel = (type?: string | null): string =>
  LOAN_TYPES.find((choice) => choice.id === type)?.name ?? LOAN_TYPES[0].name

export const penaltyModeLabel = (mode?: string | null): string =>
  PENALTY_MODES.find((choice) => choice.id === mode)?.name ?? PENALTY_MODES[0].name

/** The conditions only some types carry: the form shows a group when the type needs it. */
export const needsVariableTerms = (type?: string | null): boolean => type === 'amortizing_variable'
export const needsSteps = (type?: string | null): boolean => type === 'stepped'
export const needsDeferral = (type?: string | null): boolean => type === 'deferred_partial' || type === 'deferred_total'
