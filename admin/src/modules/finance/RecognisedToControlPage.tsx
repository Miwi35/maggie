import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import Collapse from '@mui/material/Collapse'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import { Link, Title, useCreatePath } from 'react-admin'
import { Placeholder } from '../../components/list/ListEmpty'
import { formatCents } from './accountTypes'
import { Qualification } from './Qualification'
import { NO_QUALIFICATION } from './qualificationKinds'
import type { QualificationValue } from './qualificationKinds'
import { formatDay } from './recurringOperations'
import { RECURRING_OPERATIONS_PATH } from './referenceSalary'
import { AVOIDABLE_LABEL, KEEP_LABEL } from './ToSortPage'
import { groupKey, useRecognisedToControl } from './useRecognisedToControl'
import type { RecognisedGroup, RecognisedTransaction } from './useRecognisedToControl'
import type { Satisfaction } from './useTransactionsToSort'

const SATISFACTION_CHOICES: { value: Exclude<Satisfaction, 'unrated'>; label: string }[] = [
  { value: 'keep', label: KEEP_LABEL },
  { value: 'avoidable', label: AVOIDABLE_LABEL },
]

/** Corrects a line by hand: the category (and what goes with it) is the owner's from then on. */
const CorrectDialog = ({
  transaction,
  busy,
  onSave,
  onClose,
}: {
  transaction: RecognisedTransaction
  busy: boolean
  onSave: (category: string, qualification: QualificationValue) => void
  onClose: () => void
}) => {
  const [category, setCategory] = useState<string | null>(null)
  const [qualification, setQualification] = useState<QualificationValue>(NO_QUALIFICATION)

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="correct-title">
      <DialogTitle id="correct-title">Corriger</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {transaction.label} · {formatCents(transaction.amountCents, transaction.currency)}. La ligne ne sera plus
          reconnue : elle sortira de cette liste.
        </Typography>
        <Qualification
          category={category}
          qualification={qualification}
          onChange={(next) => {
            setCategory(next.category)
            setQualification(next.qualification)
          }}
          sense={transaction.amountCents > 0 ? 'in' : 'out'}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button variant="contained" disabled={busy || category === null} onClick={() => category && onSave(category, qualification)}>
          Enregistrer
        </Button>
      </DialogActions>
    </Dialog>
  )
}

const TransactionRow = ({
  group,
  transaction,
  busy,
  onJudge,
  onCorrect,
}: {
  group: RecognisedGroup
  transaction: RecognisedTransaction
  busy: boolean
  onJudge: (verdict: Satisfaction) => void
  onCorrect: () => void
}) => {
  const createPath = useCreatePath()
  const income = transaction.amountCents > 0

  const operationLink =
    group.operationId === null
      ? null
      : group.operationType === 'rule'
        ? {
            label: 'Voir la règle',
            to: createPath({
              resource: 'categorization_rules',
              type: 'edit',
              id: `/api/categorization_rules/${group.operationId}`,
            }),
          }
        : { label: "Voir l'opération récurrente", to: RECURRING_OPERATIONS_PATH }

  return (
    <TableRow hover>
      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDay(transaction.bookedAt)}</TableCell>
      <TableCell sx={{ maxWidth: 280 }}>
        <Typography variant="body2" noWrap>
          {transaction.label}
        </Typography>
        <Typography variant="caption" color="text.secondary" noWrap component="div">
          {transaction.accountName}
        </Typography>
      </TableCell>
      <TableCell sx={{ maxWidth: 240 }}>
        <Typography variant="body2" color="text.secondary" noWrap>
          {transaction.categoryPath ?? '—'}
        </Typography>
      </TableCell>
      <TableCell
        align="right"
        sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums', color: income ? 'success.main' : undefined }}
      >
        {income ? '+' : ''}
        {formatCents(transaction.amountCents, transaction.currency)}
      </TableCell>
      <TableCell>
        {transaction.judgeable && (
          <ToggleButtonGroup
            exclusive
            size="small"
            disabled={busy}
            value={transaction.retrospect === 'unrated' ? null : transaction.retrospect}
            onChange={(_, next: Satisfaction | null) => onJudge(next ?? 'unrated')}
            aria-label="Satisfaction"
          >
            {SATISFACTION_CHOICES.map((choice) => (
              <ToggleButton key={choice.value} value={choice.value}>
                {choice.label}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
        )}
      </TableCell>
      <TableCell align="right">
        <Stack direction="row" spacing={0.5} justifyContent="flex-end" sx={{ flexWrap: 'wrap' }}>
          <Button size="small" disabled={busy} onClick={onCorrect}>
            Corriger
          </Button>
          {operationLink && (
            <Button size="small" color="inherit" component={Link} to={operationLink.to}>
              {operationLink.label}
            </Button>
          )}
          <Button
            size="small"
            color="inherit"
            component={Link}
            to={createPath({ resource: 'transactions', type: 'edit', id: `/api/transactions/${transaction.id}` })}
          >
            Voir la fiche
          </Button>
        </Stack>
      </TableCell>
    </TableRow>
  )
}

const GroupCard = ({
  group,
  open,
  busy,
  onToggle,
  onControl,
  onJudge,
  onCorrect,
}: {
  group: RecognisedGroup
  open: boolean
  busy: boolean
  onToggle: () => void
  onControl: (keepAll: boolean) => void
  onJudge: (transactionId: string, verdict: Satisfaction) => void
  onCorrect: (transaction: RecognisedTransaction) => void
}) => (
  <Card component="section" aria-label={`Groupe ${group.label}`}>
    <Stack direction="row" alignItems="center" spacing={1} sx={{ px: 1, py: 0.5, flexWrap: 'wrap', rowGap: 0.5 }}>
      <IconButton
        size="small"
        onClick={onToggle}
        aria-expanded={open}
        aria-label={`${open ? 'Replier' : 'Déplier'} ${group.label}`}
      >
        <ExpandMoreIcon sx={{ transform: open ? 'none' : 'rotate(-90deg)', transition: 'transform 150ms' }} />
      </IconButton>
      <Typography variant="subtitle1" component="h2" sx={{ flexGrow: 1, minWidth: 0 }} noWrap>
        {group.label} · {group.count}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {formatCents(group.totalCents, group.transactions[0]?.currency)}
      </Typography>
      <Button size="small" variant="contained" disabled={busy} onClick={() => onControl(false)}>
        Valider
      </Button>
      <Button size="small" variant="outlined" disabled={busy || group.unratedCount < 1} onClick={() => onControl(true)}>
        Tout à conserver
      </Button>
    </Stack>
    <Collapse in={open} unmountOnExit>
      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small" aria-label={`Transactions de ${group.label}`}>
          <TableHead>
            <TableRow>
              <TableCell>Date</TableCell>
              <TableCell>Transaction</TableCell>
              <TableCell>Catégorie</TableCell>
              <TableCell align="right">Montant</TableCell>
              <TableCell>Satisfaction</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {group.transactions.map((transaction) => (
              <TransactionRow
                key={transaction.id}
                group={group}
                transaction={transaction}
                busy={busy}
                onJudge={(verdict) => onJudge(transaction.id, verdict)}
                onCorrect={() => onCorrect(transaction)}
              />
            ))}
          </TableBody>
        </Table>
      </Box>
    </Collapse>
  </Card>
)

/** Revue › Reconnues à contrôler: what a rule or a series qualified this month, by source, to check with one gesture. */
export const RecognisedToControlPage = () => {
  const { review, loading, error, gestureError, busy, retry, control, judge, correct } = useRecognisedToControl()
  const [folded, setFolded] = useState<ReadonlySet<string>>(new Set())
  const [correcting, setCorrecting] = useState<RecognisedTransaction | null>(null)

  const toggle = (key: string) =>
    setFolded((current) => {
      const next = new Set(current)
      if (!next.delete(key)) next.add(key)

      return next
    })

  if (loading && !review) {
    return (
      <>
        <Title title="Reconnues à contrôler" />
        <LinearProgress aria-label="Chargement" />
      </>
    )
  }

  if (!review) {
    return (
      <>
        <Title title="Reconnues à contrôler" />
        <Alert severity="error" action={<Button onClick={retry}>Réessayer</Button>}>
          {error}
        </Alert>
      </>
    )
  }

  return (
    <>
      <Title title="Reconnues à contrôler" />

      {gestureError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {gestureError}
        </Alert>
      )}

      {error && (
        <Alert severity="warning" sx={{ mb: 2 }} action={<Button onClick={retry}>Réessayer</Button>}>
          {error}
        </Alert>
      )}

      {review.groups.length === 0 ? (
        <Card>
          <Placeholder
            title="Rien à contrôler ce mois-ci"
            description="Toutes les transactions reconnues par une règle ou une série ont été contrôlées."
          />
        </Card>
      ) : (
        <>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>
            {review.total} à contrôler
          </Typography>
          <Stack spacing={2}>
            {review.groups.map((group) => {
              const key = groupKey(group)

              return (
                <GroupCard
                  key={key}
                  group={group}
                  open={!folded.has(key)}
                  busy={busy}
                  onToggle={() => toggle(key)}
                  onControl={(keepAll) => void control(group.transactions.map((t) => t.id), keepAll)}
                  onJudge={(id, verdict) => void judge(id, verdict)}
                  onCorrect={setCorrecting}
                />
              )
            })}
          </Stack>
        </>
      )}

      {correcting && (
        <CorrectDialog
          key={correcting.id}
          transaction={correcting}
          busy={busy}
          onClose={() => setCorrecting(null)}
          onSave={(category, qualification) => {
            void correct(correcting.id, category, qualification).then((saved) => {
              if (saved) setCorrecting(null)
            })
          }}
        />
      )}
    </>
  )
}
