import { useCallback, useEffect, useState } from 'react'

export interface CushionAccount {
  id: string
  name: string
  balanceCents: number
  currency: string
}

export interface CushionStatus {
  state: 'building' | 'complete' | 'recharging'
  targetMonths: number
  monthlyNetIncomeCents: number
  targetCents: number
  currentCents: number
  deficitCents: number
  coveragePercent: number
  monthsCovered: number
  rechargeCapCents: number
  rechargeTargetMonths: number
  monthlyRechargeCents: number
  rechargeMonths: number
  isCappedByRechargeCap: boolean
  blocksGreenScore: boolean
  isConfigured: boolean
  accounts: CushionAccount[]
}

export type CushionConfig = Partial<
  Pick<
    CushionStatus,
    'targetMonths' | 'monthlyNetIncomeCents' | 'rechargeCapCents' | 'rechargeTargetMonths'
  >
>

function authHeaders(extra: Record<string, string> = {}) {
  const token = localStorage.getItem('token')

  return {
    Accept: 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  }
}

/** The safety net: where it stands, and what it would take to fill it. */
export function useCushionStatus() {
  const [status, setStatus] = useState<CushionStatus | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/finance/cushion-status', { headers: authHeaders() })
      if (res.ok) {
        setStatus((await res.json()) as CushionStatus)
      }
    } catch {
      // keep whatever we had
    } finally {
      setLoading(false)
    }
  }, [])

  const configure = useCallback(async (config: CushionConfig): Promise<boolean> => {
    try {
      const res = await fetch('/api/finance/cushion-config', {
        method: 'PATCH',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(config),
      })
      if (!res.ok) {
        return false
      }
      setStatus((await res.json()) as CushionStatus)

      return true
    } catch {
      return false
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return { status, loading, refresh, configure }
}

export const CUSHION_STATE_LABELS: Record<string, string> = {
  building: 'Constitution en cours',
  complete: 'Complet',
  recharging: 'En recharge',
}
