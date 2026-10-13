import { useState } from 'react'
import type { MouseEvent } from 'react'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import RepeatIcon from '@mui/icons-material/Repeat'
import { FREQUENCY_CHOICES, frequencyName } from './recurring'
import type { Frequency } from './recurring'

interface LineRecurrenceProps {
  label: string
  /** How often the line's product comes back; null when it is not recurring. */
  frequency: Frequency | null
  disabled?: boolean
  onChange: (frequency: Frequency | null) => void
}

/** The repeat chip of a list line: shows how often the product comes back, and opens the choice. */
export const LineRecurrence = ({ label, frequency, disabled, onChange }: LineRecurrenceProps) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const open = (e: MouseEvent<HTMLElement>) => setAnchor(e.currentTarget)
  const pick = (next: Frequency | null) => {
    setAnchor(null)
    if (next !== frequency) onChange(next)
  }

  return (
    <>
      {frequency ? (
        <Chip
          icon={<RepeatIcon />}
          label={frequencyName(frequency)}
          aria-label={`Récurrence de ${label} : ${frequencyName(frequency).toLowerCase()}`}
          size="small"
          color="primary"
          variant="outlined"
          disabled={disabled}
          onClick={open}
          sx={{ flexShrink: 0, mr: 1 }}
        />
      ) : (
        <IconButton
          size="small"
          aria-label={`Rendre ${label} récurrent`}
          disabled={disabled}
          onClick={open}
          sx={{ mr: 0.5 }}
        >
          <RepeatIcon fontSize="small" />
        </IconButton>
      )}
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
        <MenuItem selected={frequency === null} onClick={() => pick(null)}>
          Non
        </MenuItem>
        {FREQUENCY_CHOICES.map((choice) => (
          <MenuItem key={choice.id} selected={frequency === choice.id} onClick={() => pick(choice.id)}>
            {choice.name}
          </MenuItem>
        ))}
      </Menu>
    </>
  )
}
