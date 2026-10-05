/**
 * The French wording the finance screens are driven by.
 *
 * Mirrored from `admin/src/modules/finance/` rather than imported: the
 * journeys are a separate package with their own tsconfig, and a value copied
 * here is a value a rename has to break here too — which is the point. Only
 * what a journey actually clicks or reads belongs in this file.
 */

/** `budgetModes.ts` — the month names the `PeriodPicker` offers. */
export const MONTH_LABELS: Record<number, string> = {
  1: 'Janvier',
  2: 'Février',
  3: 'Mars',
  4: 'Avril',
  5: 'Mai',
  6: 'Juin',
  7: 'Juillet',
  8: 'Août',
  9: 'Septembre',
  10: 'Octobre',
  11: 'Novembre',
  12: 'Décembre',
}

/** `budgetModes.ts` — "Octobre 2026" for a monthly envelope, "Année 2026" for an annual one. */
export function periodLabel(mode: 'monthly' | 'annual', year: number, month?: number): string {
  return 'annual' === mode || undefined === month
    ? `Année ${year}`
    : `${MONTH_LABELS[month]} ${year}`
}

/** `useDailyScore.ts` — the title of the score banner. */
export const SCORE_LABELS = {
  green: 'Dans le vert',
  neutral: 'Dans les clous',
  orange: 'Attention',
  red: 'Dépassement',
} as const

/** `useCushionStatus.ts` — the chip beside "Matelas de sécurité". */
export const CUSHION_STATE_LABELS = {
  building: 'Constitution en cours',
  recharging: 'En recharge',
  complete: 'Complet',
} as const

/** `useBankConnections.ts` — the chip on a connection row. */
export const CONNECTION_STATUS_LABELS = {
  pending: 'En attente',
  active: 'Connectée',
  expired: 'À reconnecter',
  revoked: 'Révoquée',
} as const
