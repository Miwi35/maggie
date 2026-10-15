import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'

export const MAX_GUESTS = 999

/** The number of people, one step at a time: − / + around the value, never below one. */
export function GuestsStepper({
  label,
  value,
  onChange,
  isDefault = false,
  disabled = false,
}: {
  label: string
  value: number
  onChange: (value: number) => void
  isDefault?: boolean
  disabled?: boolean
}) {
  return (
    <Box role="group" aria-label={label} sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
      <Typography variant="body2" color="text.secondary" sx={{ mr: 1 }}>
        {label}
      </Typography>
      <IconButton
        size="small"
        aria-label={`Un convive de moins — ${label}`}
        disabled={disabled || value <= 1}
        onClick={() => onChange(value - 1)}
      >
        <RemoveIcon fontSize="small" />
      </IconButton>
      <Typography component="span" data-testid="guests-value" aria-live="polite" sx={{ minWidth: '2ch', textAlign: 'center' }}>
        {value}
      </Typography>
      <IconButton
        size="small"
        aria-label={`Un convive de plus — ${label}`}
        disabled={disabled || value >= MAX_GUESTS}
        onClick={() => onChange(value + 1)}
      >
        <AddIcon fontSize="small" />
      </IconButton>
      {isDefault && <Chip label="par défaut" size="small" variant="outlined" />}
    </Box>
  )
}
