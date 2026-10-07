import { useCallback, useEffect, useState } from 'react'

/** The other leg of a transfer, with what the owner needs to recognise it. */
export interface TransferLeg {
  id: string
  label: string
  amountCents: number
  currency: string
  bookedAt: string
  accountId: string
  accountName: string
}

export interface TransferState {
  transferKind: 'none' | 'internal'
  transferSource: 'auto' | 'manual'
  counterpart: TransferLeg | null
}

/** React-admin hands back records keyed by their IRI; the endpoints want the bare ULID. */
export const transactionIdOf = (iriOrId: string) => iriOrId.split('/').pop() ?? iriOrId

const request = async (path: string, init: RequestInit = {}) => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

/**
 * The transfer marking of one transaction, and the owner's hand on it: mark
 * it (with the other leg when there is one), take the marking off, list the
 * lines a marking can pair it with.
 *
 * Reads and writes go through `/api/finance/transactions/{id}/transfer`, never
 * through a merge-patch on the transaction, which cannot say « leave this
 * leg alone ».
 */
export function useTransactionTransfer(transactionId: string | undefined) {
  const [transfer, setTransfer] = useState<TransferState | null>(null)
  const [candidates, setCandidates] = useState<TransferLeg[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const id = transactionId === undefined ? undefined : transactionIdOf(transactionId)

  const load = useCallback(async () => {
    if (id === undefined) return
    try {
      const res = await request(`/api/finance/transactions/${id}/transfer`)
      if (!res.ok) {
        setError('Impossible de lire le virement interne')
        return
      }
      setTransfer((await res.json()) as TransferState)
      setError(null)
    } catch {
      setError('Impossible de lire le virement interne')
    }
  }, [id])

  useEffect(() => {
    setTransfer(null)
    setCandidates(null)
    void load()
  }, [load])

  const loadCandidates = useCallback(async (): Promise<TransferLeg[] | null> => {
    if (id === undefined) return null
    try {
      const res = await request(`/api/finance/transactions/${id}/transfer-candidates`)
      if (!res.ok) {
        setError('Impossible de lister les contreparties possibles')
        return null
      }
      const data = (await res.json()) as { candidates: TransferLeg[] }
      setCandidates(data.candidates)
      setError(null)

      return data.candidates
    } catch {
      setError('Impossible de lister les contreparties possibles')
      return null
    }
  }, [id])

  const write = useCallback(
    async (body: { transferKind: 'internal' | 'none'; counterpartId?: string }): Promise<boolean> => {
      if (id === undefined) return false
      setSaving(true)
      try {
        const res = await request(`/api/finance/transactions/${id}/transfer`, {
          method: 'PUT',
          body: JSON.stringify(body),
        })
        if (!res.ok) {
          const detail = (await res.json().catch(() => ({}))) as { error?: string }
          setError(detail.error ?? 'Le marquage a échoué')
          return false
        }
        setTransfer((await res.json()) as TransferState)
        setError(null)

        return true
      } catch {
        setError('Le marquage a échoué')
        return false
      } finally {
        setSaving(false)
      }
    },
    [id],
  )

  /** `counterpartId` omitted marks a single leg: the other account is not synced. */
  const mark = useCallback(
    (counterpartId?: string) => write({ transferKind: 'internal', ...(counterpartId ? { counterpartId } : {}) }),
    [write],
  )

  const release = useCallback(() => write({ transferKind: 'none' }), [write])

  return { transfer, candidates, error, saving, load, loadCandidates, mark, release }
}
