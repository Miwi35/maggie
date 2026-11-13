import { useCallback, useEffect, useState } from 'react'
import type { RaRecord } from 'react-admin'
import { accountLabel } from './accountLabel'
import type { LabelledAccount } from './accountLabel'

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

/**
 * `internal`: money moved between two of the owner's accounts — and so are
 * `advance` (taken from savings while the salary was late), `repayment` (an
 * advance given back) and `funding` (a regular top-up). `rejected`: a payment
 * the bank refused and the credit that gave it back, on one account. Every
 * kind but `none` keeps the line out of every figure.
 */
export type TransferKind = 'none' | 'internal' | 'rejected' | 'advance' | 'repayment' | 'funding'

/** The two markings the owner can put on a line by hand. */
export type MarkKind = 'internal' | 'rejected'

/** A movement between two of the owner's accounts, whatever its purpose (MAG-276). */
export const isBetweenAccounts = (kind: string | null | undefined): boolean =>
  kind === 'internal' || kind === 'advance' || kind === 'repayment' || kind === 'funding'

export interface TransferState {
  transferKind: TransferKind
  transferSource: 'auto' | 'manual'
  counterpart: TransferLeg | null
  /**
   * The savings account a single-legged advance or repayment comes from or
   * goes back to (MAG-806); null when none is named, or the line has a counterpart.
   */
  advanceSource?: { id: string; name: string } | null
}

/** The « Source » field applies to an advance or a repayment that has no other leg here. */
export const takesAdvanceSource = (transfer: TransferState | null | undefined): boolean =>
  !!transfer && (transfer.transferKind === 'advance' || transfer.transferKind === 'repayment') && transfer.counterpart === null

/** React-admin hands back records keyed by their IRI; the endpoints want the bare ULID. */
export const transactionIdOf = (iriOrId: string) => iriOrId.split('/').pop() ?? iriOrId

/** The account types an advance can come from (the API refuses any other). */
const SOURCE_ACCOUNT_TYPES = ['savings', 'investment']

/**
 * The accounts offered as the source of an advance: the savings and
 * investment ones, by bare ULID. The source already named stays offered, even
 * when the list has not answered yet.
 */
export const advanceSourceChoices = (
  accounts: RaRecord[],
  current: { id: string; name: string } | null | undefined,
): Array<{ id: string; name: string }> => {
  const choices = accounts
    .filter((account) => SOURCE_ACCOUNT_TYPES.includes(account.type as string))
    .map((account) => ({ id: transactionIdOf(String(account.id)), name: accountLabel(account as LabelledAccount) || String(account.name ?? account.id) }))
  if (current && !choices.some((choice) => choice.id === current.id)) choices.unshift(current)

  return choices
}

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

  /** `rejected` lists the lines of the same account instead of the other accounts. */
  const loadCandidates = useCallback(async (kind: MarkKind = 'internal'): Promise<TransferLeg[] | null> => {
    if (id === undefined) return null
    setCandidates(null)
    try {
      const query = kind === 'rejected' ? '?kind=rejected' : ''
      const res = await request(`/api/finance/transactions/${id}/transfer-candidates${query}`)
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
    async (body: { transferKind: TransferKind; counterpartId?: string }): Promise<boolean> => {
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

  /**
   * `counterpartId` omitted marks a single leg: the other account is not
   * synced, or the rejected payment is older than the history.
   */
  const mark = useCallback(
    (counterpartId?: string, kind: MarkKind = 'internal') =>
      write({ transferKind: kind, ...(counterpartId ? { counterpartId } : {}) }),
    [write],
  )

  const release = useCallback(() => write({ transferKind: 'none' }), [write])

  /** Names the savings account of a single-legged advance or repayment; `null` takes it off. */
  const setAdvanceSource = useCallback(
    async (accountId: string | null): Promise<boolean> => {
      if (id === undefined) return false
      setSaving(true)
      try {
        const res = await request(`/api/finance/transactions/${id}/advance-source`, {
          method: 'PUT',
          body: JSON.stringify({ accountId: accountId === null ? null : transactionIdOf(accountId) }),
        })
        if (!res.ok) {
          const detail = (await res.json().catch(() => ({}))) as { error?: string }
          setError(detail.error ?? 'La source n’a pas pu être enregistrée')
          return false
        }
        setTransfer((await res.json()) as TransferState)
        setError(null)

        return true
      } catch {
        setError('La source n’a pas pu être enregistrée')
        return false
      } finally {
        setSaving(false)
      }
    },
    [id],
  )

  return { transfer, candidates, error, saving, load, loadCandidates, mark, release, setAdvanceSource }
}

export type TransactionTransfer = ReturnType<typeof useTransactionTransfer>
