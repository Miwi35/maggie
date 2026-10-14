import { useCallback, useEffect, useState } from 'react'

export interface TreasuryMovement {
  id: string
  label: string
  amountCents: number
  status: string
  accountId: string
  accountName: string
}

export interface TreasuryDay {
  date: string
  balanceCents: number
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

/** What `GET /api/finance/treasury-outlook` answers (MAG-279): the treasury until the salary comes. */
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

/** The treasury until the salary comes, and the two settings that shape it. */
export function useTreasuryOutlook() {
  const [outlook, setOutlook] = useState<TreasuryOutlook | null>(null)
  const [settings, setSettings] = useState<TreasurySettingsState | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [outlookRes, settingsRes] = await Promise.all([
        fetch('/api/finance/treasury-outlook', { headers: authHeaders() }),
        fetch('/api/finance/treasury-settings', { headers: authHeaders() }),
      ])
      if (!outlookRes.ok || !settingsRes.ok) {
        setError('La vue trésorerie est indisponible pour le moment.')

        return
      }
      setOutlook((await outlookRes.json()) as TreasuryOutlook)
      setSettings((await settingsRes.json()) as TreasurySettingsState)
      setError(null)
    } catch {
      setError('La vue trésorerie est indisponible pour le moment.')
    } finally {
      setLoading(false)
    }
  }, [])

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
