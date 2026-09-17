import { useCallback, useEffect, useState } from 'react'

export interface Bank {
  name: string
  country: string
  logo: string | null
}

export interface BankConnection {
  id: string
  bankName: string
  country: string
  status: 'pending' | 'active' | 'expired' | 'revoked'
  consentExpiresAt: string | null
  daysBeforeExpiry: number | null
  lastSyncedAt: string | null
  needsReconnecting: boolean
}

export const CONNECTION_STATUS_LABELS: Record<string, string> = {
  pending: 'En attente',
  active: 'Connectée',
  expired: 'À reconnecter',
  revoked: 'Révoquée',
}

/** How urgent the reconnection is, said plainly. */
export function expiryNotice(connection: BankConnection): string | null {
  if (connection.needsReconnecting) {
    return 'Accès expiré — reconnectez cette banque pour reprendre la synchronisation.'
  }
  if (connection.daysBeforeExpiry == null) {
    return null
  }
  if (connection.daysBeforeExpiry <= 7) {
    return `L'accès expire dans ${connection.daysBeforeExpiry} jour(s).`
  }

  return null
}

function authHeaders(extra: Record<string, string> = {}) {
  const token = localStorage.getItem('token')

  return {
    Accept: 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  }
}

export function useBankConnections() {
  const [connections, setConnections] = useState<BankConnection[]>([])
  const [banks, setBanks] = useState<Bank[]>([])
  const [loading, setLoading] = useState(true)
  const [banksError, setBanksError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/finance/bank-connections', { headers: authHeaders() })
      if (res.ok) {
        setConnections(((await res.json()) as { connections: BankConnection[] }).connections)
      }
    } catch {
      // keep what we had
    } finally {
      setLoading(false)
    }
  }, [])

  const loadBanks = useCallback(async (country = 'FR') => {
    setBanksError(null)
    try {
      const res = await fetch(`/api/finance/banks?country=${country}`, { headers: authHeaders() })
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { error?: string }
        setBanksError(body.error ?? 'La liste des banques est indisponible.')

        return
      }
      setBanks(((await res.json()) as { banks: Bank[] }).banks)
    } catch {
      setBanksError('La liste des banques est indisponible.')
    }
  }, [])

  /** Hands back the URL to send the user to, at their bank. */
  const connect = useCallback(async (bankName: string, country = 'FR'): Promise<string | null> => {
    try {
      const res = await fetch('/api/finance/bank-connections/start', {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ bankName, country }),
      })
      if (!res.ok) {
        return null
      }

      return ((await res.json()) as { authorizationUrl: string }).authorizationUrl
    } catch {
      return null
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return { connections, banks, loading, banksError, refresh, loadBanks, connect }
}
