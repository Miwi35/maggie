import { useCallback, useEffect, useState } from 'react'

export type IncidentKind = 'direct_debit' | 'transfer'

/** One rejected payment: the debit and the credit that gave it back, read as one line. */
export interface AccountIncident {
  debitId: string | null
  creditId: string | null
  bookedAt: string
  rejectedAt: string | null
  counterpartyName: string
  amountCents: number
  kind: IncidentKind
}

export const INCIDENT_KIND_LABEL: Record<IncidentKind, string> = {
  direct_debit: 'Prélèvement rejeté',
  transfer: 'Virement rejeté',
}

/** The incidents of an account, newest first, and a way to read them again. */
export function useAccountIncidents(accountId: string | undefined) {
  const [incidents, setIncidents] = useState<AccountIncident[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!accountId) return
    try {
      const token = localStorage.getItem('token')
      const res = await fetch(`/api/accounts/${accountId}/incidents`, {
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (!res.ok) {
        setError('Impossible de lire les incidents')
        return
      }
      const body = (await res.json()) as { incidents: AccountIncident[] }
      setIncidents(body.incidents)
      setError(null)
    } catch {
      setError('Impossible de lire les incidents')
    }
  }, [accountId])

  useEffect(() => {
    load()
  }, [load])

  return { incidents, error, reload: load }
}
