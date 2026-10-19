import { useEffect, useState } from 'react'
import type { ComponentType, ReactNode } from 'react'
import { useGetList } from 'react-admin'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { CUSHION_STATE_LABELS, useCushionStatus } from './useCushionStatus'
import { fetchPeerDebtSummary, type PeerDebtSummary } from './peerDebtsApi'
import { useBankConnections } from './useBankConnections'
import { useBudgetStatus } from './useBudgetStatus'
import { useFinanceDashboard } from './useFinanceDashboard'
import { useMonthlyReview } from './useMonthlyReview'
import { useRecurringOverview } from './useRecurringOperations'
import { useSavingsAdvances } from './useSavingsAdvances'
import { useTransactionsToSort } from './useTransactionsToSort'
import { useRecognisedToControl } from './useRecognisedToControl'
import { useTreasuryOutlook } from './useTreasuryOutlook'

/** The month a block reads: the current one, as the dashboard opens on. */
const useThisMonth = () => {
  const now = new Date()

  return { year: now.getFullYear(), month: now.getMonth() + 1 }
}

const count = (n: number, one: string, many = `${one}s`) => `${n} ${n > 1 ? many : one}`

const Figure = ({ children }: { children: ReactNode }) => (
  <Typography variant="h5" sx={{ my: 0.5 }}>
    {children}
  </Typography>
)

const Hint = ({ children, warn = false }: { children: ReactNode; warn?: boolean }) => (
  <Typography variant="body2" color={warn ? 'warning.main' : 'text.secondary'}>
    {children}
  </Typography>
)

/** A block whose figures are not there: still loading, or the read failed. */
const Unavailable = ({ loading }: { loading: boolean }) =>
  loading ? <Skeleton data-testid="summary-block-loading" width="60%" /> : <Hint>Indisponible pour le moment.</Hint>

const AccountsBlock = () => {
  const { year, month } = useThisMonth()
  const { dashboard, loading } = useFinanceDashboard(year, month)

  if (!dashboard) return <Unavailable loading={loading} />
  const { balance } = dashboard

  return (
    <>
      <Figure>{formatCents(balance.totalCents)}</Figure>
      <Hint>
        {count(balance.accounts.length, 'compte')} — dont {formatCents(balance.cushionCents)} de matelas
      </Hint>
    </>
  )
}

const ImportBlock = () => <Hint>Ajouter les transactions d'un relevé CSV exporté de votre banque.</Hint>

const MonthlyReviewBlock = () => {
  const { year, month } = useThisMonth()
  const { review, loading } = useMonthlyReview(year, month)

  if (!review) return <Unavailable loading={loading} />

  return review.pendingCount > 0 ? (
    <>
      <Figure>{count(review.pendingCount, 'dépense')}</Figure>
      <Hint warn>à passer en revue ce mois-ci</Hint>
    </>
  ) : (
    <Hint>Aucune dépense en attente de revue.</Hint>
  )
}

const ToSortBlock = () => {
  const { pile, loading } = useTransactionsToSort()

  if (!pile) return <Unavailable loading={loading} />

  return pile.total > 0 ? (
    <>
      <Figure>{count(pile.total, 'transaction')}</Figure>
      <Hint warn>à qualifier</Hint>
    </>
  ) : (
    <Hint>Rien à qualifier.</Hint>
  )
}

const ToControlBlock = () => {
  const { review, loading } = useRecognisedToControl()

  if (!review) return <Unavailable loading={loading} />

  return review.total > 0 ? (
    <>
      <Figure>{count(review.total, 'transaction')}</Figure>
      <Hint warn>reconnues, à contrôler</Hint>
    </>
  ) : (
    <Hint>Rien à contrôler.</Hint>
  )
}

const ResourceCountBlock = ({ resource, one, many }: { resource: string; one: string; many?: string }) => {
  const { total, isPending, error } = useGetList(resource, { pagination: { page: 1, perPage: 1 } })

  if (error || total === undefined) return <Unavailable loading={isPending} />

  return <Figure>{count(total, one, many)}</Figure>
}

const RulesBlock = () => <ResourceCountBlock resource="categorization_rules" one="règle" />

const CategoriesBlock = () => <ResourceCountBlock resource="categories" one="catégorie" />

const ContactsBlock = () => <ResourceCountBlock resource="contacts" one="contact" />

const PeerDebtsBlock = () => {
  const [summary, setSummary] = useState<PeerDebtSummary | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    fetchPeerDebtSummary().then(setSummary).catch(() => setFailed(true))
  }, [])

  if (!summary) return <Unavailable loading={!failed} />
  if (summary.owedToMeCents === 0 && summary.iOweCents === 0) return <Hint>Personne ne te doit rien, tu ne dois rien.</Hint>

  return (
    <>
      {summary.owedToMeCents > 0 && <Figure>On te doit {formatCents(summary.owedToMeCents)}</Figure>}
      {summary.iOweCents > 0 && <Hint warn>Tu dois {formatCents(summary.iOweCents)}</Hint>}
    </>
  )
}

const LoansBlock = () => <ResourceCountBlock resource="loans" one="prêt" />

const RecurringBlock = () => {
  const { overview, error } = useRecurringOverview()

  if (!overview) return <Unavailable loading={!error} />
  const { totals, proposals } = overview

  return (
    <>
      <Figure>{count(totals.activeCount, 'opération récurrente active', 'opérations récurrentes actives')}</Figure>
      <Hint warn={proposals.length > 0}>
        {proposals.length > 0
          ? `${count(proposals.length, 'rattachement')} en attente de réponse`
          : `${formatCents(totals.monthlyCostCents)} par mois`}
      </Hint>
    </>
  )
}

const EnvelopesBlock = () => {
  const { year, month } = useThisMonth()
  const { status, loading } = useBudgetStatus(year, month)

  if (!status) return <Unavailable loading={loading} />
  const overspent = status.budgets.filter((line) => line.isOverspent).length

  return (
    <>
      <Figure>{formatCents(status.totalRemainingCents)}</Figure>
      <Hint warn={overspent > 0}>
        {overspent > 0
          ? `${count(overspent, 'enveloppe dépassée', 'enveloppes dépassées')}`
          : `restants sur ${formatCents(status.totalBudgetedCents)} budgétés`}
      </Hint>
    </>
  )
}

const CushionBlock = () => {
  const { status, loading } = useCushionStatus()

  if (!status) return <Unavailable loading={loading} />
  if (!status.isConfigured) return <Hint>Le matelas n'est pas encore réglé.</Hint>

  return (
    <>
      <Figure>
        {formatCents(status.currentCents)} / {formatCents(status.targetCents)}
      </Figure>
      <Hint>{CUSHION_STATE_LABELS[status.state] ?? status.state}</Hint>
    </>
  )
}

const AdvancesBlock = () => {
  const { data, loading, error } = useSavingsAdvances()

  if (!data) return <Unavailable loading={loading && !error} />

  return data.totalOutstandingCents > 0 ? (
    <>
      <Figure>{formatCents(data.totalOutstandingCents)}</Figure>
      <Hint warn>reste à remettre sur l'épargne</Hint>
    </>
  ) : (
    <Hint>Aucune avance à rendre.</Hint>
  )
}

const OutlookBlock = () => {
  const { outlook, loading, error } = useTreasuryOutlook()

  if (!outlook) return <Unavailable loading={loading && !error} />
  if (!outlook.salary.isConfigured) return <Hint>Indiquez le jour d'arrivée du salaire.</Hint>

  const { overdraft } = outlook

  return (
    <>
      <Figure>{formatCents(outlook.balanceCents)}</Figure>
      <Hint warn={overdraft.isForecast}>
        {overdraft.isForecast ? 'Découvert prévu avant le salaire' : "Aucun découvert prévu jusqu'au salaire"}
      </Hint>
    </>
  )
}

const IndependenceBlock = () => {
  const { year, month } = useThisMonth()
  const { dashboard, loading } = useFinanceDashboard(year, month)

  if (!dashboard) return <Unavailable loading={loading} />
  const { independence } = dashboard

  if (!independence.hasPassiveIncomeCategories) return <Hint>Aucune catégorie n'est déclarée comme rente.</Hint>
  if (!independence.isMeasurable) return <Hint>Pas encore assez de mois pour mesurer la couverture.</Hint>

  return (
    <>
      <Figure>{Math.round(independence.coveragePercent)} %</Figure>
      <Hint>du train de vie couvert par les rentes</Hint>
    </>
  )
}

const BanksBlock = () => {
  const { connections, loading } = useBankConnections()

  if (connections.length === 0) {
    return loading ? <Unavailable loading /> : <Hint>Aucune banque connectée.</Hint>
  }
  const toReconnect = connections.filter((connection) => connection.needsReconnecting).length

  return (
    <>
      <Figure>{count(connections.length, 'banque connectée', 'banques connectées')}</Figure>
      <Hint warn={toReconnect > 0}>
        {toReconnect > 0 ? `${count(toReconnect, 'consentement à renouveler', 'consentements à renouveler')}` : 'Consentements à jour'}
      </Hint>
    </>
  )
}

/** What each aspect of a menu says about itself, by the aspect's id in `FINANCE_MENUS`. */
export const SUMMARY_BLOCKS: Record<string, ComponentType> = {
  accounts: AccountsBlock,
  import: ImportBlock,
  contacts: ContactsBlock,
  'to-sort': ToSortBlock,
  'to-control': ToControlBlock,
  'monthly-review': MonthlyReviewBlock,
  rules: RulesBlock,
  recurring: RecurringBlock,
  envelopes: EnvelopesBlock,
  cushion: CushionBlock,
  advances: AdvancesBlock,
  'peer-debts': PeerDebtsBlock,
  outlook: OutlookBlock,
  loans: LoansBlock,
  independence: IndependenceBlock,
  banks: BanksBlock,
  categories: CategoriesBlock,
}
