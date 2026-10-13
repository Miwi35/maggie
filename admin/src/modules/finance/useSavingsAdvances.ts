import { useCallback, useEffect, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'

export interface SavingsAdvance {
  id: string
  sourceAccountId: string
  sourceAccountName: string
  amountCents: number
  openedOn: string
  repaidCents: number
  outstandingCents: number
  isClosed: boolean
}

export interface SavingsSource {
  sourceAccountId: string
  sourceAccountName: string
  outstandingCents: number
  openCount: number
}

/** A counter-transfer that gives one advance back, for exactly what is left of it. */
export interface RepaymentProposal {
  fromAccountId: string
  toAccountId: string
  amountCents: number
  advanceId: string
  label: string
  currency: string
  isPartial: boolean
}

export interface UnproposedAdvance {
  advanceId: string
  sourceAccountId: string
  sourceAccountName: string
  amountCents: number
  reason: 'no_checking_account' | 'insufficient_balance' | string
}

export interface SalaryDelay {
  isConfigured: boolean
  salaryExpectedDay: number | null
  referenceCents: number | null
  isLate: boolean
  delayDays: number
  shortfallCents: number
}

/** `GET /api/finance/savings-advances`. */
export interface SavingsAdvancesData {
  advances: SavingsAdvance[]
  bySource: SavingsSource[]
  totalOutstandingCents: number
  salaryDelay: SalaryDelay
  proposals: RepaymentProposal[]
  proposalsTotalCents: number
  unproposed: UnproposedAdvance[]
  unproposedCents: number
}

// An advance is settled by transfers, which are transactions: any of them moving is a reason to read again.
const TOPICS = ['/api/transactions/{id}']

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

/** Today on the owner's calendar, `YYYY-MM-DD`. */
export const localDay = (now: Date = new Date()): string =>
  [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-')

const request = async (path: string, init: RequestInit = {}) => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.headers as Record<string, string> | undefined),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

/**
 * What was taken from the savings while the salary was late, what is left to
 * give back, and the transfers that would. Read again whenever a transaction
 * moves, here or in another tab.
 */
export function useSavingsAdvances() {
  const [data, setData] = useState<SavingsAdvancesData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [givingBack, setGivingBack] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const res = await request('/api/finance/savings-advances')
      if (!res.ok) {
        setError("Impossible de lire les avances d'épargne")

        return
      }
      setData((await res.json()) as SavingsAdvancesData)
      setError(null)
    } catch {
      setError("Impossible de lire les avances d'épargne")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  useMercure(TOPICS, () => {
    void load()
  })

  const retry = useCallback(() => {
    setLoading(true)
    void load()
  }, [load])

  /**
   * « Je l'ai fait »: records the transfer as the bank would deliver it — the
   * debit of the checking account, then the credit of the savings account. The
   * server pairs the two legs and settles the advance by itself. Nothing is
   * sent to a bank: these are transactions.
   */
  const giveBack = useCallback(
    async (proposal: RepaymentProposal): Promise<boolean> => {
      setGivingBack(proposal.advanceId)
      const bookedAt = localDay()
      const leg = async (accountId: string, amountCents: number) => {
        const res = await request('/api/transactions', {
          method: 'POST',
          headers: LD,
          body: JSON.stringify({
            account: `/api/accounts/${accountId}`,
            label: proposal.label,
            amountCents,
            currency: proposal.currency,
            bookedAt,
            status: 'spent',
          }),
        })

        return res.ok
      }

      try {
        const done =
          (await leg(proposal.fromAccountId, -proposal.amountCents)) &&
          (await leg(proposal.toAccountId, proposal.amountCents))

        return done
      } catch {
        return false
      } finally {
        setGivingBack(null)
        await load()
      }
    },
    [load],
  )

  return { data, loading, error, retry, reload: load, givingBack, giveBack }
}
