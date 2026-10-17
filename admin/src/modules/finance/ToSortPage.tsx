import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import FormControlLabel from '@mui/material/FormControlLabel'
import LinearProgress from '@mui/material/LinearProgress'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { Link, Title, useCreatePath, useNotify } from 'react-admin'
import { Placeholder } from '../../components/list/ListEmpty'
import { formatCents } from './accountTypes'
import { CreateFilterDialog } from './CreateFilterDialog'
import PersonOutlineIcon from '@mui/icons-material/PersonOutline'
import { ContactPicker } from './ContactPicker'
import type { Contact } from './contactsApi'
import { bareId } from './envelopeTree'
import { Qualification } from './Qualification'
import { NO_QUALIFICATION } from './qualificationKinds'
import { PeerTransferQualifier } from './PeerTransferQualifier'
import type { QualificationValue } from './qualificationKinds'
import { formatDay } from './recurringOperations'
import { useTransactionsToSort } from './useTransactionsToSort'
import type { Satisfaction, SortAnswer, SortResult, ToSortTransaction } from './useTransactionsToSort'

const plural = (n: number, one: string, many: string) => (n > 1 ? many : one)

/** What the rule that came with an answer did, said in one line; null when no rule was involved. */
const ruleNotice = (result: SortResult | null): string | null => {
  if (!result?.rule) {
    return null
  }
  const head = result.ruleCreated ? 'Règle créée' : 'Règle appliquée'
  if (result.alsoSorted < 1) {
    return head
  }

  return `${head} · ${result.alsoSorted} ${plural(result.alsoSorted, 'autre ligne classée', 'autres lignes classées')}`
}

export const KEEP_LABEL = 'À conserver'
export const AVOIDABLE_LABEL = 'Superflue'

const SATISFACTION_CHOICES: { value: Exclude<Satisfaction, 'unrated'>; label: string }[] = [
  { value: 'keep', label: KEEP_LABEL },
  { value: 'avoidable', label: AVOIDABLE_LABEL },
]

/** Who the transfer is with (MAG-450): their name, or the question when nobody claims it yet. */
const ContactLine = ({
  transaction,
  disabled,
  onLink,
}: {
  transaction: ToSortTransaction
  disabled: boolean
  onLink: (contact: Contact) => Promise<boolean>
}) => {
  const [picking, setPicking] = useState(false)

  if (transaction.contact) {
    return (
      <Stack direction="row" spacing={0.5} alignItems="center" sx={{ mt: 1 }}>
        <PersonOutlineIcon fontSize="small" color="action" />
        <Typography variant="body2">{transaction.contact.name}</Typography>
      </Stack>
    )
  }
  if (!transaction.asksForContact) {
    return null
  }

  return (
    <>
      <Button
        size="small"
        startIcon={<PersonOutlineIcon />}
        disabled={disabled}
        onClick={() => setPicking(true)}
        sx={{ mt: 1, ml: -0.5 }}
      >
        Associer à un contact ?
      </Button>
      <ContactPicker
        open={picking}
        onClose={() => setPicking(false)}
        onPick={(contact) => {
          void onLink(contact).then((linked) => linked && setPicking(false))
        }}
      />
    </>
  )
}

const SortCard = ({
  transaction,
  sorting,
  onSort,
  onJudge,
  onLinkContact,
  onOperationCreated,
  onPeerAnswered,
}: {
  transaction: ToSortTransaction
  sorting: boolean
  onSort: (answer: SortAnswer, qualification?: QualificationValue) => void
  onJudge: (verdict: Satisfaction) => void
  onOperationCreated: () => void
  onLinkContact: (contact: Contact) => Promise<boolean>
  /** « Ce virement est » was saved: the line may leave the pile. */
  onPeerAnswered: () => void
}) => {
  const createPath = useCreatePath()
  const [always, setAlways] = useState(false)
  const [choosing, setChoosing] = useState(false)
  const [category, setCategory] = useState<string | null>(null)
  const [qualification, setQualification] = useState<QualificationValue>(NO_QUALIFICATION)
  const [creating, setCreating] = useState(false)
  const { suggestion } = transaction
  // A filed transfer only waits for « Ce virement est »: no category to ask.
  const asksCategory = transaction.reason !== 'peer_transfer'

  return (
    <Card component="section" aria-label="Transaction à qualifier">
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
          <Box>
            <Typography variant="h6">{transaction.label}</Typography>
            <Typography variant="body2" color="text.secondary">
              {formatDay(transaction.bookedAt)} · {transaction.accountName}
            </Typography>
            <ContactLine transaction={transaction} disabled={sorting} onLink={onLinkContact} />
          </Box>
          <Typography
            variant="h5"
            sx={{ fontVariantNumeric: 'tabular-nums', color: transaction.amountCents > 0 ? 'success.main' : undefined }}
          >
            {transaction.amountCents > 0 ? '+' : ''}
            {formatCents(transaction.amountCents, transaction.currency)}
          </Typography>
        </Stack>

        {transaction.asksForPeerKind && (
          <Box sx={{ my: 2 }}>
            <PeerTransferQualifier transactionId={transaction.id} label={transaction.label} onSaved={onPeerAnswered} />
          </Box>
        )}

        {asksCategory && (
          <>
            <Box sx={{ my: 2 }}>
              {suggestion ? (
                <>
                  <Typography variant="subtitle1">→ {suggestion.categoryPath}</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {suggestion.reason}
                  </Typography>
                </>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  Aucune suggestion pour cette ligne.
                </Typography>
              )}
            </Box>

            <FormControlLabel
              sx={{ mb: 1, display: 'flex' }}
              control={<Switch checked={always} disabled={sorting} onChange={(e) => setAlways(e.target.checked)} />}
              label="Toujours pour ce marchand"
            />

            {choosing && (
              <Stack spacing={1} sx={{ mb: 2 }}>
                <Qualification
                  category={category}
                  qualification={qualification}
                  onChange={(next) => {
                    setCategory(next.category)
                    setQualification(next.qualification)
                  }}
                  sense={transaction.amountCents > 0 ? 'in' : 'out'}
                />
                <Box>
                  <Button
                    variant="contained"
                    disabled={sorting || category === null}
                    onClick={() =>
                      category && onSort({ categoryId: bareId(category), alwaysForMerchant: always }, qualification)
                    }
                  >
                    Qualifier
                  </Button>
                </Box>
              </Stack>
            )}
          </>
        )}

        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
          {asksCategory && suggestion && (
            <Button
              variant="contained"
              disabled={sorting}
              onClick={() => onSort({ categoryId: suggestion.categoryId, alwaysForMerchant: always })}
            >
              Valider
            </Button>
          )}
          {asksCategory && (
            <Button
              variant={suggestion ? 'outlined' : 'contained'}
              disabled={sorting}
              onClick={() => setChoosing((open) => !open)}
            >
              Autre catégorie
            </Button>
          )}
          {asksCategory && (
            <Button disabled={sorting} onClick={() => setCreating(true)}>
              Créer un filtre
            </Button>
          )}
          <Button color="inherit" disabled={sorting} onClick={() => onSort({ internalTransfer: {} })}>
            Virement interne
          </Button>
          <Button
            color="inherit"
            component={Link}
            to={createPath({ resource: 'transactions', type: 'edit', id: `/api/transactions/${transaction.id}` })}
          >
            Voir la fiche
          </Button>
        </Stack>

        {transaction.judgeable && (
          <Box sx={{ mt: 2 }}>
            <ToggleButtonGroup
              exclusive
              size="small"
              disabled={sorting}
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
          </Box>
        )}

        {creating && (
          <CreateFilterDialog
            record={{
              id: transaction.id,
              label: transaction.label,
              amountCents: transaction.amountCents,
              category: suggestion ? `/api/categories/${suggestion.categoryId}` : null,
            }}
            applyToExistingByDefault
            onCreated={onOperationCreated}
            onClose={() => setCreating(false)}
          />
        )}
      </CardContent>
    </Card>
  )
}

const PileList = ({
  transactions,
  currentId,
  onSelect,
}: {
  transactions: ToSortTransaction[]
  currentId: string
  onSelect: (id: string) => void
}) => (
  <Card sx={{ minWidth: { md: 320 }, maxWidth: { md: 380 }, flexShrink: 0 }}>
    <List dense disablePadding aria-label="File d'attente">
      {transactions.map((transaction) => (
        <ListItemButton
          key={transaction.id}
          selected={transaction.id === currentId}
          onClick={() => onSelect(transaction.id)}
        >
          <ListItemText
            primary={transaction.label}
            secondary={`${formatDay(transaction.bookedAt)} · ${formatCents(transaction.amountCents, transaction.currency)}`}
            primaryTypographyProps={{ noWrap: true }}
          />
        </ListItemButton>
      ))}
    </List>
  </Card>
)

export const ToSortPage = () => {
  const { pile, loading, error, sortError, sorting, lastResult, retry, sort, judge, reload, linkContact } = useTransactionsToSort()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const notify = useNotify()

  const link = async (transaction: ToSortTransaction, contact: Contact): Promise<boolean> => {
    try {
      await linkContact(transaction, contact.id)
      notify(`« ${transaction.counterpartyLabel} » est lié à ${contact.name}.`, { type: 'success' })

      return true
    } catch (e) {
      notify(e instanceof Error ? e.message : 'Impossible de lier ce libellé.', { type: 'error' })

      return false
    }
  }

  if (loading && !pile) {
    return (
      <>
        <Title title="À qualifier" />
        <LinearProgress aria-label="Chargement" />
      </>
    )
  }

  if (!pile) {
    return (
      <>
        <Title title="À qualifier" />
        <Alert severity="error" action={<Button onClick={retry}>Réessayer</Button>}>
          {error}
        </Alert>
      </>
    )
  }

  const notice = ruleNotice(lastResult)
  const current = pile.transactions.find((t) => t.id === selectedId) ?? pile.transactions[0]

  return (
    <>
      <Title title="À qualifier" />

      {notice && (
        <Alert severity="success" sx={{ mb: 2 }}>
          {notice}
        </Alert>
      )}

      {sortError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {sortError}
        </Alert>
      )}

      {error && (
        <Alert severity="warning" sx={{ mb: 2 }} action={<Button onClick={retry}>Réessayer</Button>}>
          {error}
        </Alert>
      )}

      {!current ? (
        <Card>
          <Placeholder title="Rien à qualifier" description="Toutes vos transactions ont une catégorie." />
        </Card>
      ) : (
        <>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>
            {pile.total} à qualifier · {formatCents(pile.totalCents, current.currency)}
          </Typography>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'flex-start' }}>
            <Box sx={{ flexGrow: 1, minWidth: 0 }}>
              <SortCard
                key={current.id}
                transaction={current}
                sorting={sorting}
                onSort={(answer, qualification) => void sort(current.id, answer, qualification)}
                onJudge={(verdict) => void judge(current.id, verdict)}
                onOperationCreated={() => void reload()}
                onLinkContact={(contact) => link(current, contact)}
                onPeerAnswered={() => void reload()}
              />
            </Box>
            <PileList transactions={pile.transactions} currentId={current.id} onSelect={setSelectedId} />
          </Stack>
        </>
      )}
    </>
  )
}
