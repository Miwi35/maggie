import { useId } from 'react'
import Box from '@mui/material/Box'
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
import { conversionHint, packagingCaption, parsePackagings } from './quantityEntry'
import type { EntryProduct, QuantityDraft, QuantityMode } from './quantityEntry'

interface QuantityEntryProps {
  product?: EntryProduct | null
  draft: QuantityDraft
  onChange: (draft: QuantityDraft) => void
}

// The quantity of a line (MAG-573): « Quantité · Conditionnement » when the
// product is bought in a packaging, a quantity and its unit otherwise.
export function QuantityEntry({ product, draft, onChange }: QuantityEntryProps) {
  const unitLabelId = useId()
  const packaged = !!product?.packagingUnit
  const mode: QuantityMode = packaged ? draft.mode : 'quantity'
  const count = parsePackagings(draft.packagings)
  const hint = conversionHint(draft, product)

  const setPackagings = (value: number) => onChange({ ...draft, packagings: String(Math.max(1, value)), prefilled: false })

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }} data-testid="quantity-entry">
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
            onChange={(e) => onChange({ ...draft, packagings: e.target.value, prefilled: false })}
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
          {draft.prefilled && <Chip size="small" label="prérempli · recharge auto" data-testid="prefilled-chip" />}
        </Box>
      ) : (
        <Box sx={{ display: 'flex', gap: 1 }}>
          <TextField
            label="Quantité"
            type="number"
            value={draft.quantity}
            onChange={(e) => onChange({ ...draft, quantity: e.target.value })}
            sx={{ flex: 1 }}
          />
          <FormControl sx={{ flex: 1 }}>
            <InputLabel id={unitLabelId}>Unité</InputLabel>
            <Select labelId={unitLabelId} value={draft.unit} onChange={(e) => onChange({ ...draft, unit: e.target.value })} label="Unité">
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

      {hint && (
        <Typography variant="caption" color="text.secondary" data-testid="quantity-conversion">
          {hint}
        </Typography>
      )}
    </Box>
  )
}
