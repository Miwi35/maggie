import { useCallback, useEffect, useState } from 'react'
import { addDays } from '../../../dates'
import { apiUrl, headers } from '../../calendar/taskViews'
import type { FinanceScores, PeriodScore } from '../../finance/useFinanceScores'

export interface SpentPart {
  label: string
  cents: number
}

export interface DaySpending {
  totalCents: number
  /** By category, or by label for a line with none; the largest first. */
  parts: SpentPart[]
}

export interface PeriodRhythm {
  spentCents: number
  /** Zero: nothing is planned, so there is no bar. */
  plannedCents: number
  /** The day the period has reached (1-based) and the number of days it has. */
  elapsed: number
  length: number
}

export interface BilanFinance {
  day: DaySpending
  week: PeriodRhythm
  month: PeriodRhythm
}

interface ApiTransaction {
  amountCents: number
  label: string
  category?: string | null
  status?: string
  transferKind?: string
}

const members = async <T>(path: string, accept: string): Promise<T[]> => {
  const response = await fetch(`${apiUrl}${path}`, { headers: headers({ Accept: accept }) })
  if (!response.ok) throw new Error(`${path} answered ${response.status}`)
  const body = (await response.json()) as { member?: T[] } | null

  return body?.member ?? []
}

/** A real debit counts, classified or not; an internal movement or a rejected payment is not spending. */
const isSpending = (transaction: ApiTransaction): boolean =>
  transaction.amountCents < 0 && (transaction.transferKind ?? 'none') === 'none' && (transaction.status ?? 'spent') === 'spent'

export const daySpending = (transactions: ApiTransaction[], categoryNames: ReadonlyMap<string, string>): DaySpending => {
  const parts = new Map<string, number>()
  for (const transaction of transactions.filter(isSpending)) {
    const label = (transaction.category ? categoryNames.get(transaction.category) : undefined) ?? transaction.label
    parts.set(label, (parts.get(label) ?? 0) + -transaction.amountCents)
  }
  const list = [...parts].map(([label, cents]) => ({ label, cents })).sort((a, b) => b.cents - a.cents)

  return { totalCents: list.reduce((sum, part) => sum + part.cents, 0), parts: list }
}

export const periodRhythm = (period: PeriodScore, scoredDay: string): PeriodRhythm => {
  const { curve, spentCents, plannedCents } = period.rhythm
  const index = curve.findIndex((point) => point.date === scoredDay)

  return {
    spentCents,
    plannedCents,
    elapsed: index === -1 ? curve.filter((point) => point.spentCents !== null).length : index + 1,
    length: curve.length,
  }
}

/** « Vos comptes »: the day's debits, then the week and the month against the time gone. Read, never judged. */
export const useBilanFinance = (today: string, active: boolean) => {
  const [finance, setFinance] = useState<BilanFinance | null>(null)
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!active) return
    let live = true
    const day = new URLSearchParams({ 'bookedAt[after]': today, 'bookedAt[strictly_before]': addDays(today, 1), itemsPerPage: '100' })

    const read = async () => {
      const [scoresResponse, transactions] = await Promise.all([
        fetch(`${apiUrl}/finance/scores`, { headers: headers({ Accept: 'application/json' }) }),
        members<ApiTransaction>(`/transactions?${day}`, 'application/ld+json'),
      ])
      if (!scoresResponse.ok) throw new Error(`scores answered ${scoresResponse.status}`)
      const scores = (await scoresResponse.json()) as FinanceScores
      const names = new Map<string, string>()
      if (transactions.some((transaction) => transaction.category)) {
        const categories = await members<{ '@id': string; name: string }>('/categories?pagination=false', 'application/ld+json')
        categories.forEach((category) => names.set(category['@id'], category.name))
      }
      if (live) {
        setFinance({ day: daySpending(transactions, names), week: periodRhythm(scores.week, scores.date), month: periodRhythm(scores.month, scores.date) })
        setError(false)
      }
    }

    read().catch(() => {
      if (live) setError(true)
    })

    return () => {
      live = false
    }
  }, [today, active, attempt])

  const retry = useCallback(() => {
    setError(false)
    setAttempt((count) => count + 1)
  }, [])

  return { finance, error, retry }
}
