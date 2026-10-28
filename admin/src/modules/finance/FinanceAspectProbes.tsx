import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useGetList } from 'react-admin'
import { useBankConnections } from './useBankConnections'
import { useFinanceDashboard } from './useFinanceDashboard'
import { fetchProjects } from './projectsApi'

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
const useLoansProbe: Probe = () => useListHasRows('loans')
const useCategoriesProbe: Probe = () => useListHasRows('categories')

/** The list of the transactions is always worth opening, empty or not: the menu lands on it first. */
const useReviewProbe: Probe = () => true

const useProjectsProbe: Probe = () => {
  const [any, setAny] = useState<boolean | undefined>(undefined)

  useEffect(() => {
    fetchProjects()
      .then((projects) => setAny(projects.length > 0))
      .catch(() => setAny(false))
  }, [])

  return any
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
  review: useReviewProbe,
  // Budget opens on Mois; the treasury is a single page: nothing to choose between.
  month: useNoDataProbe,
  outside: useNoDataProbe,
  treasury: useNoDataProbe,
  projects: useProjectsProbe,
  loans: useLoansProbe,
  // Placements are read by the page itself: not a tab a menu opens on.
  placements: useNoDataProbe,
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
