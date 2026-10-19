import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
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
import Stack from '@mui/material/Stack'
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
import { ReviewSheetNav, useArrowNavigation, useWideReview } from './ReviewSheetNav'
import { useSequenceCursor } from './reviewSequence'
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

const SATISFACTION_NAME: Record<Exclude<Satisfaction, 'unrated'>, string> = {
  keep: KEEP_LABEL,
  avoidable: AVOIDABLE_LABEL,
}

/** The link to the rule or the series behind a group; none once the operation was deleted. */
const useOperationLink = (group: RecognisedGroup) => {
  const createPath = useCreatePath()
  if (group.operationId === null) return null

  return group.operationType === 'rule'
    ? {
        label: 'Voir la règle',
        to: createPath({ resource: 'categorization_rules', type: 'edit', id: `/api/categorization_rules/${group.operationId}` }),
      }
    : { label: "Voir l'opération récurrente", to: RECURRING_OPERATIONS_PATH }
}

/** One compact line of the list: it opens the fiche of the transaction. */
const TransactionRow = ({
  transaction,
  current,
  restoreFocus,
  onOpen,
  onFocused,
}: {
  transaction: RecognisedTransaction
  current: boolean
  restoreFocus: boolean
  onOpen: () => void
  onFocused: () => void
}) => {
  const income = transaction.amountCents > 0
  const ref = useRef<HTMLButtonElement>(null)

  // Back from the fiche (narrow window): the list comes back on the line that was open.
  useEffect(() => {
    if (current && restoreFocus) {
      ref.current?.focus()
      onFocused()
    }
    // Only when the row appears.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <ButtonBase
      ref={ref}
      component="button"
      onClick={onOpen}
      aria-label={transaction.label}
      aria-current={current ? 'true' : undefined}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        width: '100%',
        px: 1.5,
        py: 1,
        textAlign: 'left',
        borderTop: 1,
        borderColor: 'divider',
        bgcolor: current ? 'action.selected' : undefined,
        '&:hover': { bgcolor: 'action.hover' },
        '&.Mui-focusVisible': { bgcolor: 'action.focus' },
      }}
    >
      <Typography variant="body2" sx={{ whiteSpace: 'nowrap', width: 84, flexShrink: 0 }}>
        {formatDay(transaction.bookedAt)}
      </Typography>
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography variant="body2" noWrap>
          {transaction.label}
        </Typography>
        <Typography variant="caption" color="text.secondary" noWrap component="div">
          {transaction.accountName} · {transaction.categoryPath ?? '—'}
        </Typography>
      </Box>
      {transaction.retrospect !== 'unrated' && (
        <Chip size="small" variant="outlined" label={SATISFACTION_NAME[transaction.retrospect]} />
      )}
      <Typography
        variant="body2"
        sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums', color: income ? 'success.main' : undefined }}
      >
        {income ? '+' : ''}
        {formatCents(transaction.amountCents, transaction.currency)}
      </Typography>
    </ButtonBase>
  )
}

/** The fiche of one transaction: what recognised it, and the gestures on that one line. */
const TransactionSheet = ({
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
  const operationLink = useOperationLink(group)
  const income = transaction.amountCents > 0

  return (
    <Card component="section" aria-label="Transaction reconnue">
      <CardContent>
        <Stack spacing={2}>
          <Box>
            <Typography variant="h6" component="h2">
              {transaction.label}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {formatDay(transaction.bookedAt)} · {transaction.accountName}
            </Typography>
          </Box>
          <Typography
            variant="h5"
            sx={{ fontVariantNumeric: 'tabular-nums', color: income ? 'success.main' : undefined }}
          >
            {income ? '+' : ''}
            {formatCents(transaction.amountCents, transaction.currency)}
          </Typography>
          <Box>
            <Typography variant="caption" color="text.secondary" component="div">
              Catégorie
            </Typography>
            <Typography variant="body2">{transaction.categoryPath ?? '—'}</Typography>
          </Box>
          <Box>
            <Typography variant="caption" color="text.secondary" component="div">
              Reconnue par
            </Typography>
            <Typography variant="body2">{group.label}</Typography>
            {operationLink && (
              <Button size="small" color="inherit" component={Link} to={operationLink.to} sx={{ ml: -1 }}>
                {operationLink.label}
              </Button>
            )}
          </Box>
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
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            <Button variant="outlined" disabled={busy} onClick={onCorrect}>
              Corriger
            </Button>
            <Button
              color="inherit"
              component={Link}
              to={createPath({ resource: 'transactions', type: 'edit', id: `/api/transactions/${transaction.id}` })}
            >
              Voir la fiche
            </Button>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  )
}

const GroupCard = ({
  group,
  open,
  busy,
  cursorId,
  restoreFocus,
  onToggle,
  onControl,
  onOpen,
  onFocused,
}: {
  group: RecognisedGroup
  open: boolean
  busy: boolean
  cursorId: string | null
  restoreFocus: boolean
  onToggle: () => void
  onControl: (keepAll: boolean) => void
  onOpen: (transactionId: string) => void
  onFocused: () => void
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
      <Box component="ul" aria-label={`Transactions de ${group.label}`} sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {group.transactions.map((transaction) => (
          <li key={transaction.id}>
            <TransactionRow
              transaction={transaction}
              current={transaction.id === cursorId}
              restoreFocus={restoreFocus}
              onOpen={() => onOpen(transaction.id)}
              onFocused={onFocused}
            />
          </li>
        ))}
      </Box>
    </Collapse>
  </Card>
)

/** Revue › Reconnues à contrôler: what a rule or a series qualified this month, by source, to check with one gesture. */
export const RecognisedToControlPage = () => {
  const { review, loading, error, gestureError, busy, retry, control, judge, correct } = useRecognisedToControl()
  const [folded, setFolded] = useState<ReadonlySet<string>>(new Set())
  const [correcting, setCorrecting] = useState<RecognisedTransaction | null>(null)
  const [restoreFocus, setRestoreFocus] = useState(false)
  const wide = useWideReview()

  // The fiches follow the order of the list, group after group.
  const lines = (review?.groups ?? []).flatMap((group) => group.transactions.map((transaction) => ({ group, transaction })))
  const cursor = useSequenceCursor(lines.map((line) => line.transaction.id))
  useArrowNavigation(cursor)
  const current = lines.find((line) => line.transaction.id === cursor.cursorId) ?? null

  const toggle = (key: string) =>
    setFolded((currentFolded) => {
      const next = new Set(currentFolded)
      if (!next.delete(key)) next.add(key)

      return next
    })

  const open = (id: string) => {
    setRestoreFocus(false)
    cursor.open(id)
  }
  const back = () => {
    setRestoreFocus(true)
    cursor.close()
  }

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

  const list = (
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
              cursorId={cursor.cursorId}
              restoreFocus={restoreFocus}
              onToggle={() => toggle(key)}
              onControl={(keepAll) => void control(group.transactions.map((t) => t.id), keepAll)}
              onOpen={open}
              onFocused={() => setRestoreFocus(false)}
            />
          )
        })}
      </Stack>
    </>
  )

  const sheet = current && (
    <>
      <ReviewSheetNav cursor={{ ...cursor, close: back }} showBack={!wide} />
      <TransactionSheet
        key={current.transaction.id}
        group={current.group}
        transaction={current.transaction}
        busy={busy}
        onJudge={(verdict) => void judge(current.transaction.id, verdict)}
        onCorrect={() => setCorrecting(current.transaction)}
      />
    </>
  )

  let content
  if (review.groups.length === 0) {
    content = (
      <Card>
        <Placeholder
          title="Rien à contrôler ce mois-ci"
          description="Toutes les transactions reconnues par une règle ou une série ont été contrôlées."
        />
      </Card>
    )
  } else if (wide) {
    content = (
      <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(420px, 5fr) minmax(0, 7fr)', gap: 2, alignItems: 'start' }}>
        <Box sx={{ minWidth: 0, maxHeight: 'calc(100vh - 160px)', overflowY: 'auto' }}>{list}</Box>
        <Box sx={{ position: 'sticky', top: 16 }}>
          {cursor.isOpen ? (
            sheet
          ) : (
            <Card>
              <Typography color="text.secondary" sx={{ p: 3 }}>
                Choisissez une transaction dans la liste.
              </Typography>
            </Card>
          )}
        </Box>
      </Box>
    )
  } else {
    content = cursor.isOpen ? sheet : list
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

      {content}

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
