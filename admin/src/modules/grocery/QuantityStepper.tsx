import { useState } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import {
  canDecrease,
  decreaseQuantity,
  formatQuantity,
  increaseQuantity,
  parseQuantity,
  unitLabel,
} from './groceryQuantity'

interface QuantityStepperProps {
  label: string
  quantity?: number | null
  unit?: string
  packaging?: string | null
  disabled?: boolean
  onChange: (quantity: number) => void
  onInvalid: () => void
}

export function QuantityStepper({ label, quantity, unit, packaging, disabled, onChange, onInvalid }: QuantityStepperProps) {
  const [draft, setDraft] = useState<string | null>(null)

  const commitDraft = () => {
    if (draft === null) return
    const value = parseQuantity(draft)
    setDraft(null)
    if (value === null) {
      onInvalid()
      return
    }
    if (value !== quantity) onChange(value)
  }

  const shown = quantity != null ? formatQuantity(quantity) : '—'
  const unitText = quantity != null ? unitLabel(unit, quantity) : unitLabel(unit, 0)

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', flexShrink: 0, mr: 0.5 }} data-testid="quantity-stepper">
      <IconButton
        size="small"
        aria-label={`Diminuer la quantité de ${label}`}
        disabled={disabled || !canDecrease(quantity, unit)}
        onClick={() => onChange(decreaseQuantity(quantity, unit))}
      >
        <RemoveIcon fontSize="small" />
      </IconButton>
      {draft === null ? (
        <Typography
          component="button"
          type="button"
          variant="body2"
          aria-label={`Modifier la quantité de ${label}`}
          disabled={disabled}
          onClick={() => setDraft(quantity != null ? String(quantity).replace('.', ',') : '')}
          sx={{
            minWidth: 56,
            textAlign: 'center',
            background: 'none',
            border: 0,
            color: 'inherit',
            font: 'inherit',
            cursor: 'text',
            p: 0.5,
          }}
        >
          {shown}
          {unitText ? ` ${unitText}` : ''}
        </Typography>
      ) : (
        <TextField
          autoFocus
          size="small"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onFocus={(e) => e.target.select()}
          onBlur={commitDraft}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              commitDraft()
            } else if (e.key === 'Escape') {
              setDraft(null)
            }
          }}
          slotProps={{
            htmlInput: { 'aria-label': `Quantité de ${label}`, inputMode: 'decimal', style: { textAlign: 'center' } },
          }}
          sx={{ width: 72 }}
        />
      )}
      {packaging && draft === null && (
        <Typography variant="caption" color="text.secondary" data-testid="quantity-packaging" sx={{ whiteSpace: 'nowrap' }}>
          {packaging}
        </Typography>
      )}
      <IconButton
        size="small"
        aria-label={`Augmenter la quantité de ${label}`}
        disabled={disabled}
        onClick={() => onChange(increaseQuantity(quantity, unit))}
      >
        <AddIcon fontSize="small" />
      </IconButton>
    </Box>
  )
}
