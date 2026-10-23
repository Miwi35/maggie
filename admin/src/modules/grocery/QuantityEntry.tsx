import { useId } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import FormControl from '@mui/material/FormControl'
import IconButton from '@mui/material/IconButton'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import { unitLabel } from './groceryQuantity'
import { UNIT_CHOICES } from './productChoices'
import { adjustLabel, coverage, packagingCaption, parsePackagings, readable, shortfallMessage, suggestions, summaryText } from './quantityEntry'
import type { EntryProduct, QuantityDraft, QuantityMode } from './quantityEntry'
import { mealDateLabel } from './recipeNeed'
import type { RecipeNeedOfWeek } from './recipeNeed'

interface QuantityEntryProps {
  product?: EntryProduct | null
  draft: QuantityDraft
  onChange: (draft: QuantityDraft) => void
  /** What the week's meals need of the product, once read. */
  week?: RecipeNeedOfWeek
  /** The API's refusal, told under the field. */
  error?: string | null
}

// The quantity of a line (MAG-573): « Quantité · Conditionnement » when the
// product is bought in a packaging, a quantity and its unit otherwise.
export function QuantityEntry({ product, draft, onChange, week, error }: QuantityEntryProps) {
  const unitLabelId = useId()
  const packaged = !!product?.packagingUnit
  const mode: QuantityMode = packaged ? draft.mode : 'quantity'
  const count = parsePackagings(draft.packagings)
  const need = packaged ? (week?.recipeNeed ?? null) : null
  const summary = summaryText({ ...draft, mode }, product, need)
  const covers = coverage({ ...draft, mode }, product, need)
  const offers = suggestions(product, need)

  const setPackagings = (value: number) => onChange({ ...draft, packagings: String(Math.max(1, value)), source: 'user' })

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }} data-testid="quantity-entry">
      {need && (
        <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1.25, bgcolor: 'action.hover' }} data-testid="recipe-need">
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            Pour les recettes de la semaine · {readable(need.quantity, need.unit)}
          </Typography>
          {week && week.meals.length > 0 && (
            <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2 }} data-testid="recipe-need-meals">
              {week.meals.map((meal) => (
                <Typography key={`${meal.mealId}-${meal.date}`} component="li" variant="caption" color="text.secondary">
                  {meal.name} · {mealDateLabel(meal.date)} · {readable(meal.quantity, meal.unit)}
                </Typography>
              ))}
            </Box>
          )}
        </Box>
      )}

      {packaged && (
        <ToggleButtonGroup
          exclusive
          fullWidth
          size="small"
          value={mode}
          aria-label="Mode de saisie"
          onChange={(_e, value: QuantityMode | null) => value && onChange({ ...draft, mode: value })}
        >
          <ToggleButton value="quantity">Quantité</ToggleButton>
          <ToggleButton value="packagings">Conditionnement</ToggleButton>
        </ToggleButtonGroup>
      )}

      {mode === 'packagings' && product ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <IconButton
            size="small"
            aria-label="Diminuer le nombre de conditionnements"
            disabled={count === null || count <= 1}
            onClick={() => setPackagings((count ?? 1) - 1)}
          >
            <RemoveIcon fontSize="small" />
          </IconButton>
          <TextField
            size="small"
            type="number"
            value={draft.packagings}
            onChange={(e) => onChange({ ...draft, packagings: e.target.value, source: 'user' })}
            error={count === null}
            slotProps={{
              htmlInput: {
                'aria-label': `Nombre de ${unitLabel(product.packagingUnit as string, 2)}`,
                min: 1,
                step: 1,
                style: { textAlign: 'center' },
              },
            }}
            sx={{ width: 88 }}
          />
          <IconButton size="small" aria-label="Augmenter le nombre de conditionnements" onClick={() => setPackagings((count ?? 0) + 1)}>
            <AddIcon fontSize="small" />
          </IconButton>
          <Typography variant="body2" data-testid="packaging-caption">
            {packagingCaption(product, count ?? 1)}
          </Typography>
          {draft.source === 'restock' && <Chip size="small" label="prérempli · réassort" data-testid="prefilled-chip" />}
          {draft.source === 'need' && <Chip size="small" label="prérempli · besoins de la semaine" data-testid="prefilled-chip" />}
        </Box>
      ) : (
        <Box sx={{ display: 'flex', gap: 1 }}>
          <TextField
            label="Quantité"
            type="number"
            value={draft.quantity}
            onChange={(e) => onChange({ ...draft, quantity: e.target.value, source: 'user' })}
            sx={{ flex: 1 }}
          />
          <FormControl sx={{ flex: 1 }}>
            <InputLabel id={unitLabelId}>Unité</InputLabel>
            <Select labelId={unitLabelId} value={draft.unit} onChange={(e) => onChange({ ...draft, unit: e.target.value, source: 'user' })} label="Unité">
              <MenuItem value="">Aucune</MenuItem>
              {UNIT_CHOICES.map((unit) => (
                <MenuItem key={unit.id} value={unit.id}>
                  {unit.name}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
      )}

      {mode === 'quantity' && offers.length > 0 && (
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75 }} data-testid="quantity-suggestions">
          {offers.map((offer) => (
            <Chip
              key={offer.kind}
              size="small"
              variant="outlined"
              clickable
              label={offer.kind === 'need' ? `${offer.label} · besoins` : `${offer.label} · réassort`}
              onClick={() => onChange({ ...draft, quantity: String(offer.quantity), unit: offer.unit, source: 'user' })}
            />
          ))}
        </Box>
      )}

      {summary && (
        <Typography variant="body2" color="text.secondary" data-testid="quantity-summary">
          {summary}
        </Typography>
      )}

      {need && covers && !covers.covered && product && (
        <Alert
          severity="warning"
          data-testid="need-shortfall"
          action={
            <Button
              color="inherit"
              size="small"
              onClick={() => onChange({ ...draft, mode: 'packagings', packagings: String(covers.packagingsToCover), source: 'user' })}
            >
              {adjustLabel(product, covers.packagingsToCover)}
            </Button>
          }
        >
          {shortfallMessage(need)}
        </Alert>
      )}

      {error && (
        <Alert severity="error" data-testid="quantity-error">
          {error}
        </Alert>
      )}
    </Box>
  )
}
