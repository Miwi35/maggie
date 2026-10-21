import { useCallback, useEffect, useRef, useState } from 'react'
import { TOKENS } from '../../design/tokens'
import { formatCents } from './accountTypes'

export type ScoreStatus = 'green' | 'neutral' | 'orange' | 'red'

export interface ScoreReason {
  code: string
  categoryName?: string
  amountCents?: number
}

export interface RhythmPoint {
  date: string
  /** Null for the days still to come: the spent curve stops today. */
  spentCents: number | null
  expectedCents: number
}

export interface RhythmDebit {
  date: string
  label: string
  amountCents: number
  recurringOperationId: string
}

export interface Rhythm {
  budgetCents: number
  spentCents: number
  expectedSpentCents: number
  remainingCents: number
  expectedRemainingCents: number
  status: 'on_track' | 'above'
  label: string
  curve: RhythmPoint[]
  debits: RhythmDebit[]
}

export interface PeriodScore {
  status: ScoreStatus
  label: string
  reasons: ScoreReason[]
  rhythm: Rhythm
}

export interface FinanceScores {
  date: string
  week: PeriodScore
  month: PeriodScore
  global: { status: ScoreStatus; label: string; reasons: ScoreReason[] }
}

export type RhythmStatus = Rhythm['status']

/**
 * How a ring writes where the spending stands against its pace: a glyph and a
 * word beside the colour, so the status reads without it. The word is the
 * API's own `rhythm.label` when it sends one. The ring says this — never the
 * daily score (`PeriodScore.status`), which Maggie reads and the reasons explain.
 */
export const RHYTHM_META: Record<RhythmStatus, { word: string; glyph: string; color: keyof typeof TOKENS.rhythm }> = {
  on_track: { word: 'Dans le rythme', glyph: '✓', color: 'onTrack' },
  above: { word: 'Au-dessus du rythme', glyph: '!', color: 'above' },
}

/** Turns a reason code into the sentence a person reads. */
export function reasonText(reason: ScoreReason): string {
  const amount = formatCents(reason.amountCents ?? 0)

  switch (reason.code) {
    case 'mandatory_category_exceeded':
      return `${reason.categoryName} (obligatoire) dépassée de ${amount}`
    case 'optional_category_exceeded':
      return `${reason.categoryName} dépassée de ${amount}`
    case 'plans_exceed_category_budget':
      return `Les dépenses planifiées feraient dépasser ${reason.categoryName} de ${amount}`
    case 'total_budget_exceeded':
      return `Budget du mois dépassé de ${amount}`
    case 'week_budget_exceeded':
      return `Budget de la semaine dépassé de ${amount}`
    case 'above_rhythm':
      return `${amount} de plus que le rythme attendu à ce jour`
    case 'below_rhythm':
      return `${amount} de moins que le rythme attendu à ce jour`
    case 'on_rhythm':
      return `Dans le rythme attendu à ce jour (${amount} de marge)`
    case 'annual_category_exceeded':
      return `${reason.categoryName} (annuelle) dépassée de ${amount}`
    case 'plans_exceed_annual_category_budget':
      return `Les dépenses planifiées feraient dépasser ${reason.categoryName} (annuelle) de ${amount}`
    case 'cushion_incomplete':
      return `Matelas incomplet : il manque ${amount}`
    case 'below_last_year':
      return `${amount} de moins qu'à la même période l'an dernier`
    case 'above_last_year':
      return `${amount} de plus qu'à la même période l'an dernier`
    case 'spending_above_reference_salary':
      return `Dépenses au-dessus du salaire de référence de ${amount}`
    case 'no_budget':
      return 'Aucune enveloppe sur cette période : rien à comparer'
    default:
      return reason.code
  }
}

/** The share of the budget that is left; an overspent or empty budget has none. */
export const remainingShare = (remainingCents: number, budgetCents: number): number =>
  budgetCents > 0 ? Math.min(1, Math.max(0, remainingCents / budgetCents)) : 0

const pad = (n: number) => String(n).padStart(2, '0')

/**
 * The day to score for the month on screen: today for the current month (the
 * API's default, so `undefined`), the month's last day for a past one, its
 * first for a month to come.
 */
export function scoreDate(year: number, month: number, now: Date = new Date()): string | undefined {
  const asked = year * 12 + month - 1
  const current = now.getFullYear() * 12 + now.getMonth()

  if (asked === current) {
    return undefined
  }

  const day = asked < current ? new Date(year, month, 0).getDate() : 1

  return `${year}-${pad(month)}-${pad(day)}`
}

/** The week and the month scored, with their spending rhythm. */
export function useFinanceScores(year: number, month: number) {
  const [scores, setScores] = useState<FinanceScores | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++latest.current
    setLoading(true)
    try {
      const token = localStorage.getItem('token')
      const date = scoreDate(year, month)
      const res = await fetch(`/api/finance/scores${date ? `?date=${date}` : ''}`, {
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (!res.ok) {
        throw new Error(`scores answered ${res.status}`)
      }
      const body = (await res.json()) as FinanceScores
      if (request !== latest.current) {
        return
      }
      setScores(body)
      setError(false)
    } catch {
      if (request === latest.current) {
        setError(true)
      }
    } finally {
      if (request === latest.current) {
        setLoading(false)
      }
    }
  }, [year, month])

  useEffect(() => {
    setScores(null)
    refresh()
  }, [refresh])

  return { scores, loading, error, refresh }
}
