import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { MONTH_CHOICES } from './budgetModes'

interface PeriodPickerProps {
  year: number
  month: number
  onYearChange: (year: number) => void
  onMonthChange: (month: number) => void
}

/**
 * Month and year, kept together and kept small. Two controls that are read as
 * one value should never wrap onto separate lines or stretch to fill the row —
 * they are a date, not a form.
 */
export const PeriodPicker = ({
  year,
  month,
  onYearChange,
  onMonthChange,
}: PeriodPickerProps) => (
  <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
    <TextField
      select
      size="small"
      label="Mois"
      value={month}
      onChange={(e) => onMonthChange(Number(e.target.value))}
      sx={{ width: 150 }}
    >
      {MONTH_CHOICES.map((choice) => (
        <MenuItem key={choice.id} value={choice.id}>
          {choice.name}
        </MenuItem>
      ))}
    </TextField>
    <TextField
      type="number"
      size="small"
      label="Année"
      value={year}
      onChange={(e) => onYearChange(Number(e.target.value))}
      sx={{ width: 100 }}
    />
  </Stack>
)
