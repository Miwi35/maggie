import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useGetList } from 'react-admin'
import { useBankConnections } from './useBankConnections'
import { useCushionStatus } from './useCushionStatus'
import { useFinanceDashboard } from './useFinanceDashboard'
import { useMonthlyReview } from './useMonthlyReview'
import { fetchPeerDebtSummary } from './peerDebtsApi'
import { useRecognisedToControl } from './useRecognisedToControl'
import { useRecurringOverview } from './useRecurringOperations'
import { useSavingsAdvances } from './useSavingsAdvances'
import { useTransactionsToSort } from './useTransactionsToSort'
import { useTreasuryOutlook } from './useTreasuryOutlook'

/** `undefined` while the answer is not known; a failed read counts as « no data », it must not hold the page back. */
type Probe = () => boolean | undefined

const useThisMonth = () => {
  const now = new Date()

  return { year: now.getFullYear(), month: now.getMonth() + 1 }
}

const settled = (value: boolean, loading: boolean): boolean | undefined => (value ? true : loading ? undefined : false)

const useListHasRows = (resource: string): boolean | undefined => {
  const { total, isPending } = useGetList(resource, { pagination: { page: 1, perPage: 1 } })

  return settled((total ?? 0) > 0, isPending)
}

const useAccountsProbe: Probe = () => useListHasRows('accounts')
const useContactsProbe: Probe = () => useListHasRows('contacts')
const useRulesProbe: Probe = () => useListHasRows('categorization_rules')
const useLoansProbe: Probe = () => useListHasRows('loans')
const useCategoriesProbe: Probe = () => useListHasRows('categories')

const useToSortProbe: Probe = () => {
  const { pile, loading } = useTransactionsToSort()

  return settled((pile?.total ?? 0) > 0, loading)
}

const useToControlProbe: Probe = () => {
  const { review, loading } = useRecognisedToControl()

  return settled((review?.total ?? 0) > 0, loading)
}

const useMonthlyReviewProbe: Probe = () => {
  const { year, month } = useThisMonth()
  const { review, loading } = useMonthlyReview(year, month)

  return settled((review?.pendingCount ?? 0) > 0, loading)
}

const useRecurringProbe: Probe = () => {
  const { overview, error } = useRecurringOverview()

  return settled((overview?.totals.activeCount ?? 0) > 0, !overview && !error)
}

const useCushionProbe: Probe = () => {
  const { status, loading } = useCushionStatus()

  return settled(status?.isConfigured === true, loading)
}

const useAdvancesProbe: Probe = () => {
  const { data, loading, error } = useSavingsAdvances()

  return settled((data?.totalOutstandingCents ?? 0) > 0, loading && !error)
}

const usePeerDebtsProbe: Probe = () => {
  const [open, setOpen] = useState<boolean | undefined>(undefined)

  useEffect(() => {
    fetchPeerDebtSummary()
      .then((summary) => setOpen(summary.owedToMeCents > 0 || summary.iOweCents > 0))
      .catch(() => setOpen(false))
  }, [])

  return open
}

const useOutlookProbe: Probe = () => {
  const { outlook, loading, error } = useTreasuryOutlook()

  return settled(outlook?.salary.isConfigured === true, loading && !error)
}

const useIndependenceProbe: Probe = () => {
  const { year, month } = useThisMonth()
  const { dashboard, loading } = useFinanceDashboard(year, month)

  return settled(dashboard?.independence.hasPassiveIncomeCategories === true, loading)
}

const useBanksProbe: Probe = () => {
  const { connections, loading } = useBankConnections()

  return settled(connections.length > 0, loading)
}

const useNoDataProbe: Probe = () => false

/** Whether an aspect holds anything yet, by the aspect's id in `FINANCE_MENUS`: the first one that does is the tab a menu opens on. */
const PROBES: Record<string, Probe> = {
  accounts: useAccountsProbe,
  import: useNoDataProbe,
  contacts: useContactsProbe,
  'to-sort': useToSortProbe,
  'to-control': useToControlProbe,
  'monthly-review': useMonthlyReviewProbe,
  rules: useRulesProbe,
  recurring: useRecurringProbe,
  envelopes: useNoDataProbe,
  cushion: useCushionProbe,
  advances: useAdvancesProbe,
  'peer-debts': usePeerDebtsProbe,
  outlook: useOutlookProbe,
  loans: useLoansProbe,
  independence: useIndependenceProbe,
  banks: useBanksProbe,
  categories: useCategoriesProbe,
}

const AspectProbe = ({ aspectId, onResult }: { aspectId: string; onResult: (aspectId: string, hasData: boolean) => void }): ReactNode => {
  const hasData = (PROBES[aspectId] ?? useNoDataProbe)()

  useEffect(() => {
    if (hasData !== undefined) {
      onResult(aspectId, hasData)
    }
  }, [aspectId, hasData, onResult])

  return null
}

/** Reads each aspect of a menu once, side by side, and reports whether it has data. Renders nothing. */
export const AspectProbes = ({
  aspectIds,
  onResult,
}: {
  aspectIds: string[]
  onResult: (aspectId: string, hasData: boolean) => void
}) => (
  <>
    {aspectIds.map((aspectId) => (
      <AspectProbe key={aspectId} aspectId={aspectId} onResult={onResult} />
    ))}
  </>
)
