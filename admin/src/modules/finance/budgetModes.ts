export const BUDGET_MODE_CHOICES = [
  { id: 'monthly', name: 'Mensuel' },
  { id: 'annual', name: 'Annuel' },
]

export const BUDGET_MODE_LABELS: Record<string, string> = Object.fromEntries(
  BUDGET_MODE_CHOICES.map((c) => [c.id, c.name]),
)

export const MONTH_CHOICES = [
  { id: 1, name: 'Janvier' },
  { id: 2, name: 'Février' },
  { id: 3, name: 'Mars' },
  { id: 4, name: 'Avril' },
  { id: 5, name: 'Mai' },
  { id: 6, name: 'Juin' },
  { id: 7, name: 'Juillet' },
  { id: 8, name: 'Août' },
  { id: 9, name: 'Septembre' },
  { id: 10, name: 'Octobre' },
  { id: 11, name: 'Novembre' },
  { id: 12, name: 'Décembre' },
]

export const MONTH_LABELS: Record<number, string> = Object.fromEntries(
  MONTH_CHOICES.map((c) => [c.id, c.name]),
)

/** Label of the period an envelope budgets: "Juillet 2026" or "Année 2026". */
export function formatPeriod(mode: string, year: number, month?: number | null): string {
  if (mode === 'annual' || month == null) {
    return `Année ${year}`
  }
  return `${MONTH_LABELS[month] ?? month} ${year}`
}

/** Share of a budget already spent, clamped to [0, 100] for display. */
export function consumedPercent(spentCents: number, amountCents: number): number {
  if (amountCents <= 0) {
    return spentCents > 0 ? 100 : 0
  }
  return Math.min(100, Math.max(0, Math.round((spentCents / amountCents) * 100)))
}

/** An annual envelope carries no month: the API rejects one that does. */
export function clearMonthOnAnnual<T extends { mode?: unknown }>(data: T): T {
  return data.mode === 'annual' ? { ...data, month: null } : data
}
