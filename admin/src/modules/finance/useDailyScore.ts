import { useCallback, useEffect, useState } from 'react'

export interface ScoreReason {
  code: string
  categoryName?: string
  amountCents?: number
}

export interface DailyScore {
  score: 'green' | 'neutral' | 'orange' | 'red'
  year: number
  month: number
  reasons: ScoreReason[]
  budget: {
    totalBudgetedCents: number
    totalConsumedCents: number
    totalPlannedCents: number
    totalAvailableCents: number
    overspentCategories: string[]
  }
  cushion: { state: string; blocksGreenScore: boolean }
  comparison: {
    thisMonthCents: number
    sameMonthLastYearCents: number
    differenceCents: number
    isBetter: boolean
  }
}

export const SCORE_LABELS: Record<string, string> = {
  green: 'Dans le vert',
  neutral: 'Dans les clous',
  orange: 'Attention',
  red: 'Dépassement',
}

/** Severity to render each score with, in MUI's vocabulary. */
export const SCORE_SEVERITY: Record<string, 'success' | 'info' | 'warning' | 'error'> = {
  green: 'success',
  neutral: 'info',
  orange: 'warning',
  red: 'error',
}

const euros = (cents = 0) => (cents / 100).toFixed(2).replace('.', ',') + ' €'

/** Turns a reason code into the sentence a person reads. */
export function reasonText(reason: ScoreReason): string {
  const amount = euros(reason.amountCents)

  switch (reason.code) {
    case 'mandatory_category_exceeded':
      return `${reason.categoryName} (obligatoire) dépassée de ${amount}`
    case 'optional_category_exceeded':
      return `${reason.categoryName} dépassée de ${amount}`
    case 'plans_exceed_category_budget':
      return `Les dépenses planifiées feraient dépasser ${reason.categoryName} de ${amount}`
    case 'total_budget_exceeded':
      return `Budget du mois dépassé de ${amount}`
    case 'cushion_incomplete':
      return `Matelas incomplet : il manque ${amount}`
    case 'below_last_year':
      return `${amount} de moins qu'à la même période l'an dernier`
    case 'above_last_year':
      return `${amount} de plus qu'à la même période l'an dernier`
    case 'no_budget':
      return 'Aucune enveloppe sur cette période : rien à comparer'
    default:
      return reason.code
  }
}

function apiFetch(path: string) {
  const token = localStorage.getItem('token')

  return fetch(path, {
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

/** The daily signal for a period. */
export function useDailyScore(year: number, month: number) {
  const [score, setScore] = useState<DailyScore | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const res = await apiFetch(`/api/finance/daily-score?year=${year}&month=${month}`)
      if (res.ok) {
        setScore((await res.json()) as DailyScore)
      }
    } catch {
      // keep the previous score
    } finally {
      setLoading(false)
    }
  }, [year, month])

  useEffect(() => {
    refresh()
  }, [refresh])

  return { score, loading, refresh }
}
