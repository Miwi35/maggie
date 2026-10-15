export const BUDGET_MODE_CHOICES = [
  { id: 'monthly', name: 'Mensuel' },
  { id: 'annual', name: 'Annuel' },
  { id: 'one_off', name: 'Ponctuel' },
]

/** An event (Hellfest 2026): a name and an amount, no category nor period. */
export const ONE_OFF = 'one_off'

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

/** A stretch of the year a monthly envelope is budgeted differently (MAG-415); may cross the new year. */
export interface Season {
  fromMonth: number
  toMonth: number
  amountCents: number
}

const MONTH_SHORT = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.']

/** The months a season covers, in order: 11 → 2 is 11, 12, 1, 2. */
export function seasonMonths({ fromMonth, toMonth }: Pick<Season, 'fromMonth' | 'toMonth'>): number[] {
  const months = [fromMonth]
  for (let m = fromMonth; m !== toMonth; ) {
    m = (m % 12) + 1
    months.push(m)
  }
  return months
}

/** « saison nov.–févr. », or « saison juil. » for a single month. */
export function formatSeason({ fromMonth, toMonth }: Pick<Season, 'fromMonth' | 'toMonth'>): string {
  const from = MONTH_SHORT[fromMonth - 1] ?? String(fromMonth)
  const to = MONTH_SHORT[toMonth - 1] ?? String(toMonth)
  return fromMonth === toMonth ? `saison ${from}` : `saison ${from}–${to}`
}

/** The season covering `month`, if any: that month is budgeted at its amount. */
export function seasonFor(seasons: Season[] | null | undefined, month: number | null | undefined): Season | null {
  if (month == null) return null
  return (seasons ?? []).find((season) => seasonMonths(season).includes(month)) ?? null
}

/** The first month two seasons share, or null: the API refuses overlapping seasons. */
export function overlappingMonth(seasons: Array<Partial<Season> | null | undefined>): number | null {
  const taken = new Set<number>()
  for (const season of seasons) {
    if (season?.fromMonth == null || season.toMonth == null) continue
    for (const month of seasonMonths({ fromMonth: season.fromMonth, toMonth: season.toMonth })) {
      if (taken.has(month)) return month
      taken.add(month)
    }
  }
  return null
}

/** Label of the period an envelope budgets: "Juillet 2026", "Année 2026", or "Ponctuelle" for an event. */
export function formatPeriod(mode: string, year?: number | null, month?: number | null): string {
  if (mode === ONE_OFF || year == null) {
    return 'Ponctuelle'
  }
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

/**
 * What the API accepts for each mode: an annual envelope carries no month, a
 * one-off one neither category nor period, only a one-off one a name, and
 * only a monthly one seasons.
 */
export function toEnvelopePayload<T extends { mode?: unknown }>(data: T): T {
  if (data.mode === ONE_OFF) {
    return { ...data, category: null, year: null, month: null, seasonalAmounts: [] }
  }
  const rest: Record<string, unknown> = { ...data }
  delete rest.name

  return (data.mode === 'annual' ? { ...rest, month: null, seasonalAmounts: [] } : rest) as T
}
