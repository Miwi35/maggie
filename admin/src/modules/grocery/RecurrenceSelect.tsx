import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import { FREQUENCY_CHOICES } from './recurring'
import type { Frequency } from './recurring'

interface RecurrenceSelectProps {
  /** `''` is « Non »: not recurring. */
  value: Frequency | ''
  onChange: (frequency: Frequency | '') => void
  label?: string
  /** Leaves out « Non », for a choice that has to be a frequency. */
  required?: boolean
  size?: 'small' | 'medium'
  disabled?: boolean
  helperText?: string
  /** The accessible name when there is no visible `label` (a row of a list). */
  inputLabel?: string
}

export const RecurrenceSelect = ({
  value,
  onChange,
  label,
  required = false,
  size,
  disabled,
  helperText,
  inputLabel,
}: RecurrenceSelectProps) => (
  <TextField
    select
    label={label}
    value={value}
    size={size}
    disabled={disabled}
    helperText={helperText}
    onChange={(e) => onChange(e.target.value as Frequency | '')}
    // `displayEmpty`: « Non » is the empty value, and must read as « Non », not as a blank field.
    slotProps={{ htmlInput: { 'aria-label': inputLabel }, select: { displayEmpty: true }, inputLabel: { shrink: true } }}
    sx={{ minWidth: 220 }}
  >
    {!required && <MenuItem value="">Non</MenuItem>}
    {FREQUENCY_CHOICES.map((choice) => (
      <MenuItem key={choice.id} value={choice.id}>
        {choice.name}
      </MenuItem>
    ))}
  </TextField>
)
