import { useCallback, useEffect, useRef, useState } from 'react'
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

/**
 * Spent is every real debit of the period, classified or not; expected is the
 * recurring debits planned for it. Spending more than expected is normal, so
 * nothing here is a verdict (MAG-643).
 */
export interface Rhythm {
  spentCents: number
  /** The recurring debits planned up to the scored day. */
  expectedSpentCents: number
  /** The recurring debits planned for the whole period. */
  plannedCents: number
  /** Debits no category claims: counted in `spentCents`, said apart so they can be filed. */
  unclassifiedCount: number
  unclassifiedCents: number
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

/**
 * Where a ring's arc and tick sit, as shares of its circle. The scale is the
 * larger of what was spent and what is planned, so the arc never overflows and
 * the tick (the recurring debits expected so far) only exists with a plan.
 */
export const ringShares = ({ spentCents, expectedSpentCents, plannedCents }: Pick<Rhythm, 'spentCents' | 'expectedSpentCents' | 'plannedCents'>) => {
  const scale = Math.max(spentCents, plannedCents, expectedSpentCents, 0)

  return {
    spent: scale > 0 ? Math.min(1, Math.max(0, spentCents / scale)) : 0,
    expected: plannedCents > 0 && scale > 0 ? Math.min(1, Math.max(0, expectedSpentCents / scale)) : null,
  }
}

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
