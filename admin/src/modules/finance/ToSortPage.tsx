import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
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
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Title, useGetList } from 'react-admin'
import type { RaRecord } from 'react-admin'
import { Placeholder } from '../../components/list/ListEmpty'
import { formatCents } from './accountTypes'
import { categoryLabel, orderedCategories } from './categoryTree'
import type { CategoryNode } from './categoryTree'
import { bareId } from './envelopeTree'
import { formatDay } from './recurringOperations'
import { useTransactionsToSort } from './useTransactionsToSort'
import type { SortAnswer, SortResult, ToSortTransaction } from './useTransactionsToSort'

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

const CategoryPicker = ({ disabled, onPick }: { disabled: boolean; onPick: (categoryId: string) => void }) => {
  const { data = [] } = useGetList<CategoryNode & RaRecord>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })

  return (
    <Autocomplete
      fullWidth
      size="small"
      disabled={disabled}
      options={orderedCategories(data)}
      getOptionLabel={categoryLabel}
      isOptionEqualToValue={(a, b) => a.id === b.id}
      onChange={(_, category) => category && onPick(bareId(category.id))}
      renderInput={(params) => <TextField {...params} autoFocus label="Catégorie" />}
    />
  )
}

const SortCard = ({
  transaction,
  sorting,
  error,
  onSort,
}: {
  transaction: ToSortTransaction
  sorting: boolean
  error: string | null
  onSort: (answer: SortAnswer) => void
}) => {
  const [always, setAlways] = useState(false)
  const [choosing, setChoosing] = useState(false)
  const { suggestion } = transaction

  return (
    <Card component="section" aria-label="Transaction à trier">
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
          <Box>
            <Typography variant="h6">{transaction.label}</Typography>
            <Typography variant="body2" color="text.secondary">
              {formatDay(transaction.bookedAt)} · {transaction.accountName}
            </Typography>
          </Box>
          <Typography
            variant="h5"
            sx={{ fontVariantNumeric: 'tabular-nums', color: transaction.amountCents > 0 ? 'success.main' : undefined }}
          >
            {transaction.amountCents > 0 ? '+' : ''}
            {formatCents(transaction.amountCents, transaction.currency)}
          </Typography>
        </Stack>

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

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <FormControlLabel
          sx={{ mb: 1, display: 'flex' }}
          control={<Switch checked={always} disabled={sorting} onChange={(e) => setAlways(e.target.checked)} />}
          label="Toujours pour ce marchand"
        />

        {choosing && (
          <Box sx={{ mb: 2 }}>
            <CategoryPicker
              disabled={sorting}
              onPick={(categoryId) => onSort({ categoryId, alwaysForMerchant: always })}
            />
          </Box>
        )}

        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
          {suggestion && (
            <Button
              variant="contained"
              disabled={sorting}
              onClick={() => onSort({ categoryId: suggestion.categoryId, alwaysForMerchant: always })}
            >
              Valider
            </Button>
          )}
          <Button variant={suggestion ? 'outlined' : 'contained'} disabled={sorting} onClick={() => setChoosing(true)}>
            Autre catégorie
          </Button>
          <Button color="inherit" disabled={sorting} onClick={() => onSort({ internalTransfer: {} })}>
            Virement interne
          </Button>
        </Stack>
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
  const { pile, loading, error, sortError, sorting, lastResult, retry, sort } = useTransactionsToSort()
  const [selectedId, setSelectedId] = useState<string | null>(null)

  if (loading && !pile) {
    return (
      <>
        <Title title="À trier" />
        <LinearProgress aria-label="Chargement" />
      </>
    )
  }

  if (!pile) {
    return (
      <>
        <Title title="À trier" />
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
      <Title title="À trier" />

      {notice && (
        <Alert severity="success" sx={{ mb: 2 }}>
          {notice}
        </Alert>
      )}

      {error && (
        <Alert severity="warning" sx={{ mb: 2 }} action={<Button onClick={retry}>Réessayer</Button>}>
          {error}
        </Alert>
      )}

      {!current ? (
        <Card>
          <Placeholder title="Rien à trier" description="Toutes vos transactions ont une catégorie." />
        </Card>
      ) : (
        <>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>
            {pile.total} à trier
          </Typography>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'flex-start' }}>
            <Box sx={{ flexGrow: 1, minWidth: 0 }}>
              <SortCard
                key={current.id}
                transaction={current}
                sorting={sorting}
                error={sortError}
                onSort={(answer) => void sort(current.id, answer)}
              />
            </Box>
            <PileList transactions={pile.transactions} currentId={current.id} onSelect={setSelectedId} />
          </Stack>
        </>
      )}
    </>
  )
}
