import { useCallback, useEffect, useRef, useState } from 'react'

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
  /** When the owner said it was dealt with (« Régularisé », MAG-424); null while in the pile. */
  settledAt: string | null
}

export const INCIDENT_KIND_LABEL: Record<IncidentKind, string> = {
  direct_debit: 'Prélèvement rejeté',
  transfer: 'Virement rejeté',
}

const LOAD_ERROR = 'Impossible de lire les incidents'
const SETTLE_ERROR = 'Impossible de régulariser ce rejet'
const UNSETTLE_ERROR = 'Impossible de remettre ce rejet dans la pile'

/** Either leg names the rejection: the API settles both. */
export const incidentTransactionId = (incident: AccountIncident): string =>
  (incident.debitId ?? incident.creditId) as string

const request = (path: string, method = 'GET') => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

/**
 * The incidents of an account, newest first — the pile, and apart the
 * rejections already settled — with the gesture that settles one and the one
 * that puts it back.
 */
export function useAccountIncidents(accountId: string | undefined) {
  const [incidents, setIncidents] = useState<AccountIncident[] | null>(null)
  const [settled, setSettled] = useState<AccountIncident[]>([])
  const [error, setError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  // Only the latest read may land: a slow answer must not put a settled line back.
  const latestRead = useRef(0)

  const load = useCallback(async () => {
    if (!accountId) return
    const read = ++latestRead.current
    try {
      const [pile, done] = await Promise.all([
        request(`/api/accounts/${accountId}/incidents`),
        request(`/api/accounts/${accountId}/incidents?settled=true`),
      ])
      if (read !== latestRead.current) return
      if (!pile.ok || !done.ok) {
        setError(LOAD_ERROR)
        return
      }
      const [pileBody, doneBody] = (await Promise.all([pile.json(), done.json()])) as {
        incidents: AccountIncident[]
      }[]
      if (read !== latestRead.current) return
      setIncidents(pileBody.incidents)
      setSettled(doneBody.incidents ?? [])
      setError(null)
    } catch {
      if (read === latestRead.current) setError(LOAD_ERROR)
    }
  }, [accountId])

  const change = useCallback(
    async (incident: AccountIncident, method: 'POST' | 'DELETE'): Promise<boolean> => {
      const failure = method === 'POST' ? SETTLE_ERROR : UNSETTLE_ERROR
      try {
        const res = await request(
          `/api/finance/transactions/${incidentTransactionId(incident)}/rejection-settlement`,
          method,
        )
        if (!res.ok) {
          setActionError(failure)
          return false
        }
        setActionError(null)
        await load()
        return true
      } catch {
        setActionError(failure)
        return false
      }
    },
    [load],
  )

  const settle = useCallback((incident: AccountIncident) => change(incident, 'POST'), [change])
  const unsettle = useCallback((incident: AccountIncident) => change(incident, 'DELETE'), [change])

  useEffect(() => {
    load()
  }, [load])

  return { incidents, settled, error, actionError, reload: load, settle, unsettle }
}
