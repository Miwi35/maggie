import { Fragment, useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import Collapse from '@mui/material/Collapse'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import EditIcon from '@mui/icons-material/Edit'
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown'
import KeyboardArrowRightIcon from '@mui/icons-material/KeyboardArrowRight'
import { Title, useDelete, useGetList, useNotify } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { Placeholder } from '../../components/list/ListEmpty'
import { Amount } from './AmountField'
import { formatCents } from './accountTypes'
import { RecurringOperationDialog } from './RecurringOperationDialog'
import { RecurringCatchUpButton } from './RecurringCatchUp'
import { ProposalsPanel, SuggestionsPanel } from './RecurringPanels'
import { RecurringSeriesTransactions } from './RecurringSeriesTransactions'
import {
  SOURCE_LABEL,
  describePriceChange,
  describeSchedule,
  formatDay,
  idOf,
  readIgnored,
  seriesIri,
  writeIgnored,
} from './recurringOperations'
import { useRecurringLink, useRecurringOverview, useRecurringSuggestions } from './useRecurringOperations'
import type { RaRecord } from 'react-admin'
import type { RecurringFormValues } from './RecurringOperationDialog'
import type {
  ProposalClaim,
  RecurringProposal,
  RecurringSeries,
  RecurringStored,
  RecurringSuggestion,
} from './recurringOperations'

const TOPICS = ['/api/recurring_operations/{id}', '/api/transactions/{id}']

const capitalise = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

const Stat = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <Box sx={{ flex: '1 1 200px' }}>
    <Typography variant="caption" color="text.secondary">
      {label}
    </Typography>
    <Typography variant="h6" component="div" sx={{ fontVariantNumeric: 'tabular-nums' }}>
      {children}
    </Typography>
  </Box>
)

const dialogValuesOf = (series: RecurringSeries, stored: RecurringStored): Partial<RecurringFormValues> => ({
  label: series.label,
  counterpartyName: stored.counterpartyName ?? '',
  labelPattern: stored.labelPattern ?? '',
  referenceAmountCents: series.referenceAmountCents,
  account: `/api/accounts/${series.accountId}`,
  category: `/api/categories/${series.categoryId}`,
  period: series.period,
  dayRule: series.dayRule,
  anchorOn: stored.anchorOn.slice(0, 10),
  amountTolerancePercent: stored.amountTolerancePercent,
  dateToleranceDays: stored.dateToleranceDays,
  endsOn: series.endsOn?.slice(0, 10) ?? '',
})

const suggestionValuesOf = (s: RecurringSuggestion): Partial<RecurringFormValues> => ({
  label: s.label,
  counterpartyName: s.counterpartyName ?? '',
  labelPattern: s.labelPattern ?? '',
  referenceAmountCents: s.referenceAmountCents,
  account: `/api/accounts/${s.accountId}`,
  category: s.categoryId ? `/api/categories/${s.categoryId}` : '',
  period: s.period,
  dayRule: s.dayRule,
  anchorOn: s.anchorOn,
})

const SeriesBadges = ({ series }: { series: RecurringSeries }) => (
  <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
    {series.missedOccurrence && (
      <Chip
        size="small"
        color="warning"
        label="Échéance manquée"
        title={`Prévue le ${formatDay(series.missedOccurrence.occurrenceOn)}, ${series.missedOccurrence.daysOverdue} jour(s) de retard`}
      />
    )}
    {series.priceChange && (
      <Chip
        size="small"
        color="warning"
        variant="outlined"
        label={`${series.priceChange.percent < 0 ? 'Baisse' : 'Hausse'} de prix · ${describePriceChange(series.priceChange, series.currency)}`}
        title={`Depuis l’échéance du ${formatDay(series.priceChange.since)}`}
      />
    )}
  </Stack>
)

/** The stored list is served by the search index, which can trail the overview: re-read it a few times. */
const DETAIL_RETRY_MS = 1500
const DETAIL_MAX_RETRIES = 5
const detailRetryDelay = (attempt: number) => DETAIL_RETRY_MS * (attempt + 1)

/**
 * The recurring operations (MAG-308): what each series costs, when it falls
 * due, the attachments waiting for a yes and the series the history suggests.
 * Everything is read from the overview Maggie reads too; the page decides nothing.
 */
export const RecurringOperationsPage = () => {
  const notify = useNotify()
  const { overview, error, reload } = useRecurringOverview()
  const { suggestions, error: suggestionsError, reload: reloadSuggestions, accept } = useRecurringSuggestions()
  const { attach, saving } = useRecurringLink()
  const [deleteOne, { isPending: deleting }] = useDelete()
  const { data: storedList = [], refetch } = useGetList<RaRecord>('recurring_operations', {
    pagination: { page: 1, perPage: 200 },
  })
  const [ignored, setIgnored] = useState<string[]>(readIgnored)
  const [dialog, setDialog] = useState<{ recordId?: string; initial?: Partial<RecurringFormValues> } | null>(null)
  const [toDelete, setToDelete] = useState<RecurringSeries | null>(null)
  const [accepting, setAccepting] = useState(false)
  const [expanded, setExpanded] = useState<string[]>([])
  const [transactionsTick, setTransactionsTick] = useState(0)

  const toggle = (id: string) =>
    setExpanded((open) => (open.includes(id) ? open.filter((other) => other !== id) : [...open, id]))
  const [accountFilter, setAccountFilter] = useState('')

  const detailRetries = useRef(0)
  const [retryTick, setRetryTick] = useState(0)

  const reloadAll = () => {
    detailRetries.current = 0
    setRetryTick((tick) => tick + 1)
    setTransactionsTick((tick) => tick + 1)
    reload()
    reloadSuggestions()
    refetch()
  }
  useMercure(TOPICS, reloadAll)

  const stored = new Map<string, RecurringStored>(
    storedList.map((record) => [idOf(String(record.id)), record as unknown as RecurringStored]),
  )
  const operations = overview?.operations ?? []
  const accounts = [...new Map(operations.map((o) => [o.accountId, o.accountName])).entries()]
  const shown = accountFilter ? operations.filter((o) => o.accountId === accountFilter) : operations
  const detailMissing = operations.some((operation) => !stored.has(operation.id))

  useEffect(() => {
    if (!detailMissing) {
      detailRetries.current = 0

      return
    }
    if (detailRetries.current >= DETAIL_MAX_RETRIES) return
    const timer = setTimeout(() => {
      detailRetries.current += 1
      refetch()
      setRetryTick((tick) => tick + 1)
    }, detailRetryDelay(detailRetries.current))

    return () => clearTimeout(timer)
  }, [detailMissing, retryTick, refetch])
  const currencyOf = (seriesId: string | null) => operations.find((o) => o.id === seriesId)?.currency ?? 'EUR'
  const proposals = (overview?.proposals ?? []).filter((p) => !ignored.includes(p.transactionId))

  const ignore = (proposal: RecurringProposal) => {
    const next = [...ignored, proposal.transactionId]
    setIgnored(next)
    writeIgnored(next)
  }

  const attachProposal = async (proposal: RecurringProposal, claim: ProposalClaim) => {
    if (!claim.recurringOperationId) return
    const ok = await attach(proposal.transactionId, claim.recurringOperationId, claim.occurrenceOn)
    if (!ok) {
      notify('Impossible de rattacher cette transaction', { type: 'error' })

      return
    }
    notify(`« ${proposal.label} » rattachée à « ${claim.recurringOperationLabel} »`, { type: 'info' })
    reloadAll()
  }

  const acceptSuggestion = async (suggestion: RecurringSuggestion) => {
    // A series needs a category: when the history names none, the owner picks it in the form.
    if (suggestion.categoryId === null) {
      setDialog({ initial: suggestionValuesOf(suggestion) })

      return
    }
    setAccepting(true)
    const result = await accept([suggestion])
    setAccepting(false)
    if (result === null || result.created === 0) {
      notify('Impossible de créer la série', { type: 'error' })

      return
    }
    notify(`Série « ${suggestion.label} » créée, ${result.attached} transaction(s) rattachée(s)`, { type: 'info' })
    reloadAll()
  }

  const iriOf = (series: RecurringSeries) => seriesIri(series.id)

  const confirmDelete = () => {
    if (!toDelete) return
    const iri = iriOf(toDelete)
    deleteOne(
      'recurring_operations',
      { id: iri, previousData: { id: iri } },
      {
        onSuccess: () => {
          setToDelete(null)
          notify('Série supprimée', { type: 'info' })
          reloadAll()
        },
        onError: () => notify('Impossible de supprimer la série', { type: 'error' }),
      },
    )
  }

  const totals = overview?.totals

  return (
    <>
      <Title title="Opérations récurrentes" />

      <Stack direction="row" spacing={1} justifyContent="flex-end" flexWrap="wrap" useFlexGap sx={{ mb: 2 }}>
        <RecurringCatchUpButton onDone={reloadAll} />
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setDialog({})}>
          Nouvelle opération récurrente
        </Button>
      </Stack>

      {error && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={reload}>
              Réessayer
            </Button>
          }
        >
          {error}
        </Alert>
      )}
      {suggestionsError && !error && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {suggestionsError}
        </Alert>
      )}

      {!overview && !error && <LinearProgress aria-label="Chargement" />}

      {overview && totals && (
        <Card sx={{ mb: 3 }}>
          <CardContent>
            <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
              <Stat label="Dépenses par mois">{formatCents(totals.monthlyCostCents)}</Stat>
              <Stat label="Dépenses par an">{formatCents(totals.yearlyCostCents)}</Stat>
              <Stat label="Recettes par mois">{formatCents(totals.monthlyIncomeCents)}</Stat>
              <Stat label="Séries actives">{totals.activeCount}</Stat>
            </Stack>
          </CardContent>
        </Card>
      )}

      {proposals.length > 0 && (
        <ProposalsPanel
          proposals={proposals}
          currencyOf={currencyOf}
          busy={saving}
          onAttach={attachProposal}
          onIgnore={ignore}
        />
      )}

      {suggestions !== null && suggestions.length > 0 && (
        <SuggestionsPanel suggestions={suggestions} busy={accepting} onAccept={acceptSuggestion} />
      )}

      {overview && operations.length === 0 && (
        <Placeholder
          title="Aucune opération récurrente pour l’instant"
          description="Une opération récurrente est un loyer, un abonnement, un salaire : Maggie rattache ses transactions, en lit le coût et prévient quand une échéance manque ou qu’un prix bouge."
          action={
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => setDialog({})}>
              Créer une opération récurrente
            </Button>
          }
        />
      )}

      {accounts.length > 1 && (
        <TextField
          select
          size="small"
          label="Compte"
          value={accountFilter}
          onChange={(event) => setAccountFilter(event.target.value)}
          sx={{ mb: 2, minWidth: 240 }}
          slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
        >
          <MenuItem value="">Tous les comptes</MenuItem>
          {accounts.map(([id, name]) => (
            <MenuItem key={id} value={id}>
              {name}
            </MenuItem>
          ))}
        </TextField>
      )}

      {operations.length > 0 && (
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small" aria-label="Opérations récurrentes">
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox" />
                <TableCell>Libellé</TableCell>
                <TableCell>Contrepartie</TableCell>
                <TableCell>Catégorie</TableCell>
                <TableCell>Compte</TableCell>
                <TableCell>Périodicité</TableCell>
                <TableCell align="right">Montant de référence</TableCell>
                <TableCell align="right">Coût</TableCell>
                <TableCell>Prochaine échéance</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {shown.map((series) => {
                const detail = stored.get(series.id)
                const open = expanded.includes(series.id)

                return (
                  <Fragment key={series.id}>
                  <TableRow hover>
                    <TableCell padding="checkbox">
                      <IconButton
                        size="small"
                        aria-label={`${open ? 'Replier' : 'Dérouler'} les transactions de ${series.label}`}
                        aria-expanded={open}
                        onClick={() => toggle(series.id)}
                      >
                        {open ? <KeyboardArrowDownIcon fontSize="small" /> : <KeyboardArrowRightIcon fontSize="small" />}
                      </IconButton>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {series.label}
                      </Typography>
                      <SeriesBadges series={series} />
                    </TableCell>
                    <TableCell>{detail?.counterpartyName ?? detail?.labelPattern ?? '—'}</TableCell>
                    <TableCell>{series.categoryName}</TableCell>
                    <TableCell>
                      {series.accountName}
                      {series.accountBank && (
                        <Typography variant="caption" color="text.secondary" component="div">
                          {series.accountBank}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      {capitalise(describeSchedule(series.period, series.dayRule, detail?.anchorOn))}
                    </TableCell>
                    <TableCell align="right">
                      <Amount cents={series.referenceAmountCents} currency={series.currency} signed />
                      <Typography variant="caption" color="text.secondary">
                        {SOURCE_LABEL[series.referenceSource]}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">
                      <Box sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {formatCents(series.monthlyCostCents, series.currency)} / mois
                      </Box>
                      <Typography variant="caption" color="text.secondary" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {formatCents(series.yearlyCostCents, series.currency)} / an
                      </Typography>
                    </TableCell>
                    <TableCell>
                      {series.nextOccurrenceOn ? formatDay(series.nextOccurrenceOn) : 'Série terminée'}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      <IconButton
                        size="small"
                        aria-label={`Modifier ${series.label}`}
                        disabled={!detail}
                        onClick={() =>
                          detail &&
                          setDialog({ recordId: iriOf(series), initial: dialogValuesOf(series, detail) })
                        }
                      >
                        <EditIcon fontSize="small" />
                      </IconButton>
                      <IconButton size="small" aria-label={`Supprimer ${series.label}`} onClick={() => setToDelete(series)}>
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </TableCell>
                  </TableRow>
                  <TableRow>
                    <TableCell colSpan={10} sx={{ py: 0, borderBottom: open ? undefined : 'none' }}>
                      <Collapse in={open} unmountOnExit>
                        <Box sx={{ py: 1.5 }}>
                          <RecurringSeriesTransactions
                            seriesId={series.id}
                            currency={series.currency}
                            reloadKey={transactionsTick}
                          />
                        </Box>
                      </Collapse>
                    </TableCell>
                  </TableRow>
                  </Fragment>
                )
              })}
            </TableBody>
          </Table>
        </Box>
      )}

      {dialog && (
        <RecurringOperationDialog
          recordId={dialog.recordId}
          initial={dialog.initial}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null)
            notify(dialog.recordId ? 'Série enregistrée' : 'Série créée', { type: 'info' })
            reloadAll()
          }}
        />
      )}

      {toDelete && (
        <Dialog open onClose={() => setToDelete(null)} aria-labelledby="recurring-delete-title">
          <DialogTitle id="recurring-delete-title">Supprimer « {toDelete.label} » ?</DialogTitle>
          <DialogContent>
            <Typography>
              Les {toDelete.attachmentCount} transaction(s) rattachées sont conservées ; elles ne seront plus rattachées à la série.
            </Typography>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setToDelete(null)}>Annuler</Button>
            <Button color="error" variant="contained" disabled={deleting} onClick={confirmDelete}>
              Supprimer
            </Button>
          </DialogActions>
        </Dialog>
      )}
    </>
  )
}
