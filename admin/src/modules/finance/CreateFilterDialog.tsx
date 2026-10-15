import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { useCreate, useGetList, useNotify } from 'react-admin'
import type { RaRecord } from 'react-admin'
import { DIRECTION_CHOICES, MATCH_TYPE_CHOICES, RULE_NEEDS_IDENTITY, idOf, ruleIdentifies } from './categorizationRules'
import { categoryLabel, orderedCategories } from './categoryTree'
import type { CategoryNode } from './categoryTree'
import { labelPatternFrom } from './recurringOperations'
import { RuleMatchesPanel } from './RuleMatchesPanel'
import type { RulePreviewCriteria } from './useCategorizationRulePreview'

type CategoryOption = CategoryNode & RaRecord

/** The sign of the amount tells which side of the account the rule looks at. */
const directionOf = (amountCents: number) => (amountCents < 0 ? 'debit' : amountCents > 0 ? 'credit' : 'any')

/** Euros typed in a field, as the unsigned cents the API stores; null when empty or not a number. */
const centsOf = (euros: string): number | null => {
  const value = parseFloat(euros)

  return euros.trim() === '' || !Number.isFinite(value) || value < 0 ? null : Math.round(value * 100)
}

/**
 * « Créer un filtre » from a transaction: a categorization rule opened filled
 * from the line — its label, its direction, its category — with the lines it
 * would catch listed live as the pattern changes.
 */
export const CreateFilterDialog = ({ record, onClose }: { record: RaRecord; onClose: () => void }) => {
  const notify = useNotify()
  const [create, { isPending }] = useCreate()
  const [labelPattern, setLabelPattern] = useState(() => labelPatternFrom(String(record.label ?? '')))
  const [matchType, setMatchType] = useState('contains')
  const [direction, setDirection] = useState(() => directionOf(Number(record.amountCents ?? 0)))
  const [minAmount, setMinAmount] = useState('')
  const [maxAmount, setMaxAmount] = useState('')
  const [category, setCategory] = useState<string | null>((record.category as string | null | undefined) || null)
  const [applyToExisting, setApplyToExisting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const { data: categories = [] } = useGetList<CategoryOption>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })
  const options = orderedCategories(categories)
  const selected = options.find((c) => c.id === category) ?? null

  const minAmountCents = centsOf(minAmount)
  const maxAmountCents = centsOf(maxAmount)
  const identified = ruleIdentifies(labelPattern, direction, minAmountCents, maxAmountCents)

  const criteria: RulePreviewCriteria = {
    labelPattern,
    matchType,
    direction,
    minAmountCents,
    maxAmountCents,
    categoryId: category ? idOf(category) : null,
    priority: 0,
    isActive: true,
    ruleId: null,
  }
  const valid = identified && category !== null

  const submit = () => {
    setError(null)
    create(
      'categorization_rules',
      {
        data: {
          labelPattern: labelPattern.trim() === '' ? null : labelPattern.trim(),
          matchType,
          direction,
          category,
          minAmountCents,
          maxAmountCents,
          priority: 0,
          isActive: true,
          applyToExisting,
        },
      },
      {
        onSuccess: () => {
          notify('Filtre créé', { type: 'info' })
          onClose()
        },
        onError: (e: unknown) => setError(e instanceof Error && e.message ? e.message : 'Impossible de créer le filtre'),
      },
    )
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md" aria-labelledby="create-filter-title">
      <DialogTitle id="create-filter-title">Créer un filtre</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <TextField
              select
              size="small"
              label="Le libellé…"
              value={matchType}
              onChange={(event) => setMatchType(event.target.value)}
              sx={{ flex: '1 1 160px' }}
            >
              {MATCH_TYPE_CHOICES.map((c) => (
                <MenuItem key={c.id} value={c.id}>
                  {c.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              size="small"
              label="…ce texte"
              value={labelPattern}
              onChange={(event) => setLabelPattern(event.target.value)}
              sx={{ flex: '2 1 240px' }}
              helperText="Le nom du commerçant suffit : CARREFOUR, SNCF, NETFLIX… Facultatif avec un sens et un montant."
              error={!identified}
            />
            <TextField
              select
              size="small"
              label="Sens du mouvement"
              value={direction}
              onChange={(event) => setDirection(event.target.value)}
              sx={{ flex: '1 1 160px' }}
            >
              {DIRECTION_CHOICES.map((c) => (
                <MenuItem key={c.id} value={c.id}>
                  {c.name}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <TextField
              size="small"
              type="number"
              label="Montant minimum (€)"
              value={minAmount}
              onChange={(event) => setMinAmount(event.target.value)}
              slotProps={{ htmlInput: { min: 0, step: 0.01 } }}
              sx={{ flex: '1 1 160px' }}
              helperText="Facultatif, hors signe."
            />
            <TextField
              size="small"
              type="number"
              label="Montant maximum (€)"
              value={maxAmount}
              onChange={(event) => setMaxAmount(event.target.value)}
              slotProps={{ htmlInput: { min: 0, step: 0.01 } }}
              sx={{ flex: '1 1 160px' }}
              helperText="Facultatif, hors signe."
            />
          </Stack>
          {!identified && <Alert severity="info" variant="outlined">{RULE_NEEDS_IDENTITY}</Alert>}

          <Autocomplete
            options={options}
            value={selected}
            onChange={(_, next) => setCategory(next ? String(next.id) : null)}
            getOptionLabel={categoryLabel}
            isOptionEqualToValue={(a, b) => a.id === b.id}
            renderInput={(params) => (
              <TextField
                {...params}
                size="small"
                label="Catégorie"
                helperText="Une catégorie posée à la main ne sera jamais écrasée par ce filtre."
              />
            )}
          />

          <FormControlLabel
            label="Appliquer aux transactions existantes"
            control={<Checkbox checked={applyToExisting} onChange={(event) => setApplyToExisting(event.target.checked)} />}
          />

          <RuleMatchesPanel criteria={criteria} />

          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button variant="contained" disabled={!valid || isPending} onClick={submit}>
          Créer le filtre
        </Button>
      </DialogActions>
    </Dialog>
  )
}
