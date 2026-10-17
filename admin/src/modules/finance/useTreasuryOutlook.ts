import { useCallback, useEffect, useRef, useState } from 'react'

export interface TreasuryMovement {
  id: string
  label: string
  amountCents: number
  status: string
  accountId: string
  accountName: string
  /** `transaction` for a planned or committed line, `recurring` for the occurrence of a series. */
  source: 'transaction' | 'recurring'
}

export interface TreasuryDay {
  date: string
  balanceCents: number
  /** What came in since today, up to and including this day. */
  cumulativeInflowCents: number
  /** What went out since today, as a positive amount. */
  cumulativeOutflowCents: number
  movements: TreasuryMovement[]
}

export interface TreasuryAlternative {
  accountId: string
  accountName: string
  balanceCents: number
  amountCents: number
  isCushion: boolean
}

export interface TreasuryAdvice {
  amountCents: number
  sourceAccountId: string | null
  sourceAccountName: string | null
  sourceBalanceCents: number
  sourceAmountCents: number
  isCushion: boolean
  alternatives: TreasuryAlternative[]
  uncoveredCents: number
  touchesCushion: boolean
}

/** What `GET /api/finance/treasury-outlook` answers (MAG-279): the treasury over the horizon asked for (MAG-506). */
export interface TreasuryOutlook {
  today: string
  salary: {
    isConfigured: boolean
    expectedOn: string | null
    referenceCents: number | null
    isReceived: boolean
    isLate: boolean
    delayDays: number
    shortfallCents: number
  }
  balanceCents: number
  projection: TreasuryDay[]
  balanceBeforeSalary: { onDate: string; balanceCents: number } | null
  overdraft: {
    isForecast: boolean
    onDate: string | null
    worstBalanceCents: number
    limitCents: number
  }
  advice: TreasuryAdvice | null
  outstandingAdvancesCents: number
}

export interface TreasurySettingsState {
  salaryExpectedDay: number | null
  overdraftLimitCents: number
}

export type TreasurySettingsChange = {
  overdraftLimitCents?: number
  salaryExpectedDay?: number | null
}

export type TreasurySaveResult = { ok: true } | { ok: false; message: string }

function authHeaders(extra: Record<string, string> = {}) {
  const token = localStorage.getItem('token')

  return {
    Accept: 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  }
}

/** The topics that move a projected balance: a transaction, an account, a recurring transaction. */
export const TREASURY_TOPICS = [
  '/api/transactions/{id}',
  '/api/accounts/{id}',
  '/api/recurring_operations/{id}',
]

/** The horizons the owner can pick, in months; `null` is the API's default, the end of the next month. */
export const TREASURY_HORIZONS: ReadonlyArray<{ months: number | null; label: string }> = [
  { months: null, label: 'Fin du mois suivant' },
  { months: 1, label: '1 mois' },
  { months: 3, label: '3 mois' },
  { months: 6, label: '6 mois' },
  { months: 12, label: '1 an' },
]

/** The treasury over a horizon, and the two settings that shape it. */
export function useTreasuryOutlook(months: number | null = null) {
  const [outlook, setOutlook] = useState<TreasuryOutlook | null>(null)
  const [settings, setSettings] = useState<TreasurySettingsState | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const mine = ++latest.current
    setLoading(true)
    try {
      const [outlookRes, settingsRes] = await Promise.all([
        fetch(months === null ? '/api/finance/treasury-outlook' : `/api/finance/treasury-outlook?months=${months}`, {
          headers: authHeaders(),
        }),
        fetch('/api/finance/treasury-settings', { headers: authHeaders() }),
      ])
      if (!outlookRes.ok || !settingsRes.ok) {
        if (mine === latest.current) setError('La vue trésorerie est indisponible pour le moment.')

        return
      }
      const nextOutlook = (await outlookRes.json()) as TreasuryOutlook
      const nextSettings = (await settingsRes.json()) as TreasurySettingsState
      if (mine !== latest.current) return
      setOutlook(nextOutlook)
      setSettings(nextSettings)
      setError(null)
    } catch {
      if (mine === latest.current) setError('La vue trésorerie est indisponible pour le moment.')
    } finally {
      if (mine === latest.current) setLoading(false)
    }
  }, [months])

  const save = useCallback(
    async (change: TreasurySettingsChange): Promise<TreasurySaveResult> => {
      try {
        const res = await fetch('/api/finance/treasury-settings', {
          method: 'PATCH',
          headers: authHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify(change),
        })
        if (!res.ok) {
          const body = (await res.json().catch(() => null)) as { error?: string } | null

          return { ok: false, message: body?.error ?? 'Réglage refusé.' }
        }
      } catch {
        return { ok: false, message: 'Réglage impossible pour le moment.' }
      }
      await refresh()

      return { ok: true }
    },
    [refresh],
  )

  useEffect(() => {
    refresh()
  }, [refresh])

  return { outlook, settings, loading, error, refresh, save }
}
