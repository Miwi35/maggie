import { formatCents } from './accountTypes'

export type RecurrencePeriod = 'weekly' | 'monthly' | 'quarterly' | 'yearly'
export type DayRule = 'fixed_day' | 'last_day_of_month'
export type ReferenceSource = 'declared' | 'measured'
export type MatchReason = 'late' | 'early' | 'amount_up' | 'amount_down' | 'ambiguous'

export const DEFAULT_AMOUNT_TOLERANCE_PERCENT = 20
export const DEFAULT_DATE_TOLERANCE_DAYS = 5

export interface PriceChange {
  previousCents: number
  currentCents: number
  percent: number
  since: string
}

/** One series as `GET /api/finance/recurring-operations/overview` reads it. */
export interface RecurringSeries {
  id: string
  label: string
  categoryId: string
  categoryName: string
  accountId: string
  accountName: string
  accountBank: string | null
  period: RecurrencePeriod
  dayRule: DayRule
  referenceAmountCents: number
  referenceSource: ReferenceSource
  currency: string
  monthlyCostCents: number
  yearlyCostCents: number
  endsOn: string | null
  nextOccurrenceOn: string | null
  lastAttachedOn: string | null
  attachmentCount: number
  missedOccurrence: { occurrenceOn: string; daysOverdue: number } | null
  priceChange: PriceChange | null
}

/** What the overview does not carry, read from the resource itself. */
export interface RecurringStored {
  id: string
  counterpartyName: string | null
  labelPattern: string | null
  anchorOn: string
  amountTolerancePercent: number
  dateToleranceDays: number
}

export interface RecurringTotals {
  monthlyCostCents: number
  yearlyCostCents: number
  monthlyIncomeCents: number
  activeCount: number
}

export interface ProposalClaim {
  recurringOperationId: string | null
  recurringOperationLabel?: string | null
  occurrenceOn?: string | null
  dateGapDays?: number
  referenceAmountCents?: number
  amountChangePercent?: number
}

export interface RecurringProposal extends ProposalClaim {
  transactionId: string
  label: string
  bookedAt: string
  amountCents: number
  reason: MatchReason
  candidates?: ProposalClaim[]
}

export interface RecurringOverview {
  operations: RecurringSeries[]
  proposals: RecurringProposal[]
  totals: RecurringTotals
}

export interface RecurringSuggestion {
  label: string
  counterpartyName: string | null
  counterpartyKey: string | null
  labelPattern: string | null
  accountId: string
  accountName: string
  period: RecurrencePeriod
  anchorOn: string
  dayRule: DayRule
  referenceAmountCents: number
  amountSpreadPercent: number
  occurrences: number
  categoryId: string | null
  categoryName: string | null
  samples: string[]
}

export interface AcceptResult {
  created: number
  labels: string[]
  attached: number
  proposed: number
}

export interface AttachReport {
  dryRun: boolean
  scanned: number
  attached: number
  proposed: number
  attachments: RecurringProposal[]
  proposals: RecurringProposal[]
}

const WEEKDAYS = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi']
const MONTHS = [
  'janvier',
  'février',
  'mars',
  'avril',
  'mai',
  'juin',
  'juillet',
  'août',
  'septembre',
  'octobre',
  'novembre',
  'décembre',
]

export const PERIOD_CHOICES: { id: RecurrencePeriod; name: string }[] = [
  { id: 'weekly', name: 'Chaque semaine' },
  { id: 'monthly', name: 'Chaque mois' },
  { id: 'quarterly', name: 'Chaque trimestre' },
  { id: 'yearly', name: 'Chaque année' },
]

export const DAY_RULE_CHOICES: { id: DayRule; name: string }[] = [
  { id: 'fixed_day', name: 'Un jour fixe' },
  { id: 'last_day_of_month', name: 'Le dernier jour du mois' },
]

export const SOURCE_LABEL: Record<ReferenceSource, string> = {
  declared: 'déclaré',
  measured: 'mesuré',
}

/** `2026-10-12` or `2026-10-12T00:00:00+00:00` → `[2026, 10, 12]`. */
const parts = (iso: string): [number, number, number] => {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number)

  return [y, m, d]
}

/** `2026-10-12` → `12/10/2026`. */
export const formatDay = (iso: string): string => iso.slice(0, 10).split('-').reverse().join('/')

/** `2026-10-12` → `12/10`. */
export const formatShortDay = (iso: string): string => formatDay(iso).slice(0, 5)

const ordinal = (day: number): string => (day === 1 ? '1er' : String(day))

/**
 * The expected day in plain French: « le 5 de chaque mois », « le dernier jour
 * du mois ». Without the series' anchor only the period can be said.
 */
export function describeSchedule(period: RecurrencePeriod, dayRule: DayRule, anchorOn?: string | null): string {
  const lastDay = dayRule === 'last_day_of_month'
  const [y, m, d] = anchorOn ? parts(anchorOn) : [0, 0, 0]
  const known = Boolean(anchorOn)

  switch (period) {
    case 'weekly':
      return known ? `chaque ${WEEKDAYS[new Date(Date.UTC(y, m - 1, d)).getUTCDay()]}` : 'chaque semaine'
    case 'monthly':
      if (lastDay) return 'le dernier jour du mois'

      return known ? `le ${ordinal(d)} de chaque mois` : 'chaque mois'
    case 'quarterly':
      if (lastDay) return 'le dernier jour du mois, tous les trois mois'

      return known ? `le ${ordinal(d)}, tous les trois mois` : 'chaque trimestre'
    case 'yearly':
      if (lastDay) return known ? `le dernier jour de ${MONTHS[m - 1]}, chaque année` : 'chaque année'

      return known ? `le ${ordinal(d)} ${MONTHS[m - 1]} de chaque année` : 'chaque année'
  }
}

/** « 11 transactions Netflix depuis janvier, les rattacher ? » */
export function describeHistory(name: string, lines: { bookedAt: string }[]): string {
  const oldest = lines.map((l) => l.bookedAt.slice(0, 10)).sort()[0]
  const [y, m] = parts(oldest)
  const since = y === new Date().getFullYear() ? MONTHS[m - 1] : `${MONTHS[m - 1]} ${y}`

  return `${lines.length} transaction${lines.length > 1 ? 's' : ''} ${name} depuis ${since}, les rattacher ?`
}

const money = (cents: number, currency = 'EUR') => formatCents(Math.abs(cents), currency)

const signedPercent = (percent: number) => `${percent > 0 ? '+' : ''}${percent} %`

/** « 13,49 € → 17,99 €, +33 % » */
export function describePriceChange(change: PriceChange, currency = 'EUR'): string {
  return `${money(change.previousCents, currency)} → ${money(change.currentCents, currency)}, ${signedPercent(change.percent)}`
}

/** Why the pass only proposed the line, in the owner's words. */
export function describeReason(p: RecurringProposal, currency = 'EUR'): string {
  const days = Math.abs(p.dateGapDays ?? 0)
  const plural = days > 1 ? 's' : ''

  switch (p.reason) {
    case 'late':
      return `arrivée ${days} jour${plural} après l'échéance du ${formatShortDay(p.occurrenceOn ?? p.bookedAt)}`
    case 'early':
      return `arrivée ${days} jour${plural} avant l'échéance du ${formatShortDay(p.occurrenceOn ?? p.bookedAt)}`
    case 'amount_up':
    case 'amount_down': {
      const change = money(p.referenceAmountCents ?? 0, currency)
      const kind = p.reason === 'amount_up' ? 'hausse' : 'baisse'

      return `${kind} de prix ${change} → ${money(p.amountCents, currency)}, ${signedPercent(p.amountChangePercent ?? 0)}`
    }
    case 'ambiguous':
      return 'plusieurs séries peuvent la réclamer'
  }
}

/** The claims a proposal can be attached to: one, or the candidates of an ambiguous line. */
export function claimsOf(p: RecurringProposal): ProposalClaim[] {
  if (p.reason === 'ambiguous') return p.candidates ?? []

  return p.recurringOperationId ? [p] : []
}

const IGNORED_KEY = 'maggie.finance.recurring.ignoredProposals'

/** The API has no « ignore »: the lines the owner dismissed are remembered here, per browser. */
export function readIgnored(): string[] {
  try {
    const raw = localStorage.getItem(IGNORED_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []

    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : []
  } catch {
    return []
  }
}

export function writeIgnored(ids: string[]): void {
  try {
    localStorage.setItem(IGNORED_KEY, JSON.stringify(ids))
  } catch {
    // Storage full or blocked: the proposal simply comes back on reload.
  }
}

const VARIABLE_PARTS = [
  /\b\d{4}-\d{2}-\d{2}\b/g,
  /\b\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?\b/g,
  /\b(?:CB|CARTE|CARD)\b[\s*:]*X?\d+/gi,
  /\bR[ée]f(?:[ée]rence)?\b\.?\s*:?\s*\S+/gi,
  /\bN°\s*\S+/gi,
  /\S*\d{4,}\S*/g,
]

/**
 * What a bank label reads as when the payee is unknown: the longest stretch of
 * the label that holds none of the parts that change from one booking to the
 * next (dates, card and reference numbers). A fragment of the label, as the
 * server matches the pattern by « contains ». Left whole if nothing else remains.
 */
export function labelPatternFrom(label: string): string {
  const whole = label.replace(/\s+/g, ' ').trim()
  const stretches = VARIABLE_PARTS.reduce((text, part) => text.replace(part, '\u0000'), whole)
    .split('\u0000')
    .map((stretch) => stretch.replace(/^[\s\-*:/.,]+|[\s\-*:/.,]+$/g, ''))
    .filter((stretch) => stretch !== '')
  const longest = stretches.reduce((best, stretch) => (stretch.length > best.length ? stretch : best), '')

  return longest === '' ? whole : longest
}

/** React-admin hands back resources as IRIs; the endpoints want the bare ULID. */
export const idOf = (iri: string) => iri.split('/').pop() ?? iri

export const seriesIri = (id: string) => `/api/recurring_operations/${idOf(id)}`
