import { Fragment, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import MenuItem from '@mui/material/MenuItem'
import Tab from '@mui/material/Tab'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableRow from '@mui/material/TableRow'
import Tabs from '@mui/material/Tabs'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline'
import HelpOutlineIcon from '@mui/icons-material/HelpOutline'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import ScheduleIcon from '@mui/icons-material/Schedule'
import TrendingUpIcon from '@mui/icons-material/TrendingUp'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { CreateButton, ReferenceField, TopToolbar, Title, useCreatePath, useGetList, useGetOne } from 'react-admin'
import type { ReactElement } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMercure } from '../../hooks/useMercure'
import { Placeholder } from '../../components/list/ListEmpty'
import { statusLabel } from './transactionStatuses'
import { Amount } from './AmountField'
import { TransactionPills } from './TransactionPills'
import { isBetweenAccounts, transactionIdOf } from './useTransactionTransfer'
import { AccountIncidents } from './AccountIncidents'
import { useAccountIncidents } from './useAccountIncidents'
import { CategoryPathField } from './CategoryPathField'
import { accountLabel } from './accountLabel'
import { formatCents } from './accountTypes'
import { dayTitle, dueDay, monthTitle } from './timelineFormat'
import { useAccountTimeline } from './useAccountTimeline'
import type { MissedOccurrence, TimelineStatus, TimelineTransaction } from './useAccountTimeline'

const TRANSACTION_TOPICS = ['/api/transactions/{id}']

const STATUS_ICON: Record<TimelineStatus, ReactElement> = {
  spent: <CheckCircleOutlineIcon fontSize="small" color="success" />,
  committed: <LockOutlinedIcon fontSize="small" color="primary" />,
  planned: <ScheduleIcon fontSize="small" color="action" />,
  to_arbitrate: <HelpOutlineIcon fontSize="small" color="warning" />,
}

const AddTransactionButton = ({ accountIri }: { accountIri: string }) => (
  <CreateButton
    resource="transactions"
    label="Ajouter une transaction"
    state={{ record: { account: accountIri } }}
  />
)

/**
 * The invitation of an account with nothing in it. It carries its own button:
 * the toolbar is not drawn in its place, so the screen would be a dead end
 * (MAG-245).
 */
export const AccountTransactionsEmpty = ({
  accountIri,
  title = 'Aucune transaction sur ce compte',
}: {
  accountIri: string
  title?: string
}) => <Placeholder title={title} action={<AddTransactionButton accountIri={accountIri} />} />

/** Switching account keeps the screen: only the id of the route changes. */
const AccountSelect = ({ accountId, fallbackName }: { accountId: string; fallbackName?: string }) => {
  const navigate = useNavigate()
  const { data } = useGetList('accounts', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  })
  const options = (data ?? []).map((account) => ({
    id: transactionIdOf(String(account.id)),
    label: accountLabel(account) || String(account.id),
  }))
  // The list may not have answered yet: the select must still hold the account on screen.
  if (!options.some((option) => option.id === accountId)) {
    options.unshift({ id: accountId, label: fallbackName ?? 'Compte' })
  }

  return (
    <TextField
      select
      size="small"
      label="Compte"
      value={accountId}
      onChange={(event) => navigate(`/accounts/${event.target.value}/transactions`)}
      sx={{ minWidth: 260 }}
    >
      {options.map((option) => (
        <MenuItem key={option.id} value={option.id}>
          {option.label}
        </MenuItem>
      ))}
    </TextField>
  )
}

/** The account on the other side of a movement between two of the owner's accounts. */
const counterpartSentence = (line: TimelineTransaction): string | null => {
  if (!isBetweenAccounts(line.transferKind)) return null
  if (line.counterpartAccount === null) return 'Sans contrepartie : l’autre compte n’est pas suivi'

  return `${line.amountCents > 0 ? 'depuis' : 'vers'} ${line.counterpartAccount.name}`
}

const TimelineLine = ({ line }: { line: TimelineTransaction }) => {
  const navigate = useNavigate()
  const createPath = useCreatePath()
  const iri = `/api/transactions/${line.id}`
  const counterpart = counterpartSentence(line)
  // The shape the list's pills read.
  const pillRecord = {
    id: iri,
    transferKind: line.transferKind,
    amountCents: line.amountCents,
    recurringOperation: line.recurringOperationId ? `/api/recurring_operations/${line.recurringOperationId}` : null,
    category: line.categoryId ? `/api/categories/${line.categoryId}` : null,
  }

  return (
    <TableRow hover sx={{ cursor: 'pointer' }} onClick={() => navigate(createPath({ resource: 'transactions', type: 'edit', id: iri }))}>
      <TableCell>
        <span>{line.label}</span>
        <TransactionPills record={pillRecord} />
        {line.recurringIncrease !== null && (
          <Chip
            size="small"
            color="warning"
            variant="outlined"
            icon={<TrendingUpIcon />}
            label={`+${line.recurringIncrease.percent} %`}
            title={`Avant : ${formatCents(line.recurringIncrease.previousCents, line.currency)}`}
            sx={{ ml: 1 }}
          />
        )}
        {counterpart !== null && (
          <Box sx={{ typography: 'caption', color: 'text.secondary' }}>{counterpart}</Box>
        )}
      </TableCell>
      <TableCell>
        {line.categoryId !== null && (
          <ReferenceField
            record={{ category: `/api/categories/${line.categoryId}` }}
            source="category"
            reference="categories"
            link={false}
          >
            <CategoryPathField />
          </ReferenceField>
        )}
      </TableCell>
      <TableCell>
        <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
          {STATUS_ICON[line.status]}
          {statusLabel(line.status, line.amountCents)}
        </Box>
      </TableCell>
      <TableCell align="right">
        <Amount cents={line.amountCents} currency={line.currency} signed />
      </TableCell>
    </TableRow>
  )
}

/** A recurring operation whose due date went by without a line. */
const MissedLine = ({ missed, currency }: { missed: MissedOccurrence; currency: string }) => (
  <TableRow>
    <TableCell colSpan={3}>
      <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, color: 'warning.main' }}>
        <WarningAmberIcon fontSize="small" />
        {missed.label} attendu {dueDay(missed.expectedOn)} — en retard
      </Box>
    </TableCell>
    <TableCell align="right">
      <Amount cents={missed.amountCents} currency={currency} signed />
    </TableCell>
  </TableRow>
)

/**
 * One account, day by day, with the real status of each line (MAG-422). The
 * current month first; « Mois précédent » reads the month of the latest
 * earlier line. Rejected payments keep their own tab.
 */
export const AccountTransactionsView = () => {
  const { id } = useParams()
  const accountIri = `/api/accounts/${id}`
  // The app bar's title names the account with its bank and currency (MAG-501, MAG-387).
  const { data: record } = useGetOne('accounts', { id: accountIri })
  const accountName = record ? accountLabel(record) : 'Compte'

  const [searchParams] = useSearchParams()
  const [tab, setTab] = useState(searchParams.get('tab') === 'incidents' ? 1 : 0)
  const timeline = useAccountTimeline(id)
  const {
    incidents,
    settled,
    error: incidentsError,
    actionError,
    reload: reloadIncidents,
    settle,
    unsettle,
  } = useAccountIncidents(id)
  // A rejection is detected when its credit lands: the pair leaves the timeline
  // and enters the incidents on the same message, so both are read again.
  useMercure(TRANSACTION_TOPICS, () => {
    void timeline.reload()
    void reloadIncidents()
  })

  const { account, pages, loading, error, previousMonth } = timeline
  const hasLines = pages.some((page) => page.days.length > 0)
  const loaded = pages.length > 0

  return (
    <>
      <Title title={`Transactions — ${accountName}`} />
      <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 2, mb: 1 }}>
        <AccountSelect accountId={id ?? ''} fallbackName={account?.name} />
        {account && (
          <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
            <Typography variant="h6" component="span">
              {account.name}
            </Typography>
            <Typography component="span" aria-label="Solde du compte" sx={{ fontVariantNumeric: 'tabular-nums' }}>
              {formatCents(account.balanceCents, account.currency)}
            </Typography>
          </Box>
        )}
        {hasLines && (
          <Box sx={{ ml: 'auto' }}>
            <TopToolbar>
              <AddTransactionButton accountIri={accountIri} />
            </TopToolbar>
          </Box>
        )}
      </Box>
      <Tabs value={tab} onChange={(_, value: number) => setTab(value)} sx={{ mb: 1 }}>
        <Tab label="Transactions" />
        <Tab label={`Incidents${incidents === null ? '' : ` (${incidents.length})`}`} />
      </Tabs>
      <Box hidden={tab !== 0}>
        {error !== null && (
          <Alert
            severity="error"
            sx={{ mb: 2 }}
            action={
              <Button color="inherit" size="small" onClick={() => void timeline.reload()}>
                Réessayer
              </Button>
            }
          >
            {error}
          </Alert>
        )}
        {!loaded && error === null && <Typography color="text.secondary">Chargement…</Typography>}
        {loaded && !hasLines && (
          <AccountTransactionsEmpty
            accountIri={accountIri}
            {...(previousMonth !== null && {
              title: 'Aucune transaction ce mois-ci',
            })}
          />
        )}
        {hasLines && (
          <Table size="small">
            <TableBody>
              {pages.filter((page) => page.days.length > 0).map((page) => (
                <Fragment key={page.month}>
                  <TableRow>
                    <TableCell colSpan={4} data-month={page.month} sx={{ typography: 'subtitle1', fontWeight: 600, pt: 3 }}>
                      {monthTitle(page.month)}
                    </TableCell>
                  </TableRow>
                  {page.days.map((day) => (
                    <Fragment key={day.date}>
                      <TableRow>
                        <TableCell colSpan={4} data-day={day.date} sx={{ bgcolor: 'action.hover', fontWeight: 500 }}>
                          {dayTitle(day.date)}
                        </TableCell>
                      </TableRow>
                      {day.missedOccurrences.map((missed) => (
                        <MissedLine key={missed.recurringOperationId} missed={missed} currency={page.account.currency} />
                      ))}
                      {day.transactions.map((line) => (
                        <TimelineLine key={line.id} line={line} />
                      ))}
                    </Fragment>
                  ))}
                </Fragment>
              ))}
            </TableBody>
          </Table>
        )}
        {previousMonth !== null && (
          <Box sx={{ mt: 2, textAlign: 'center' }}>
            <Button variant="outlined" disabled={loading} onClick={() => void timeline.loadPrevious()}>
              Mois précédent
            </Button>
          </Box>
        )}
      </Box>
      <Box hidden={tab !== 1}>
        <AccountIncidents
          incidents={incidents}
          settled={settled}
          error={incidentsError}
          actionError={actionError}
          onSettle={settle}
          onUnsettle={unsettle}
        />
      </Box>
    </>
  )
}
