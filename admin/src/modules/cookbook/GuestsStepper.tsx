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
  name = label,
  hideLabel = false,
  value,
  onChange,
  isDefault = false,
  disabled = false,
}: {
  label: string
  /** What the group is called to a screen reader, when « Convives » alone would not tell two steppers apart. */
  name?: string
  hideLabel?: boolean
  value: number
  onChange: (value: number) => void
  isDefault?: boolean
  disabled?: boolean
}) {
  return (
    <Box role="group" aria-label={name} sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
      {!hideLabel && (
        <Typography variant="body2" color="text.secondary" sx={{ mr: 1 }}>
          {label}
        </Typography>
      )}
      <IconButton
        size="small"
        aria-label={`Un convive de moins — ${name}`}
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
        aria-label={`Un convive de plus — ${name}`}
        disabled={disabled || value >= MAX_GUESTS}
        onClick={() => onChange(value + 1)}
      >
        <AddIcon fontSize="small" />
      </IconButton>
      {isDefault && <Chip label="par défaut" size="small" variant="outlined" />}
    </Box>
  )
}
