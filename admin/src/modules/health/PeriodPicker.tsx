import Box from '@mui/material/Box'
import { PERIOD_LABELS, PERIODS } from './suiviSeries'
import type { Period } from './suiviSeries'

export interface PeriodPickerProps {
  period: Period
  onChange: (period: Period) => void
}

/** « 7 j · 30 j · 90 j · 1 an », one of them checked. */
export const PeriodPicker = ({ period, onChange }: PeriodPickerProps) => (
  <Box role="radiogroup" aria-label="Période" sx={{ display: 'flex', gap: '2px', p: '3px', borderRadius: 2.5, bgcolor: 'background.default' }}>
    {PERIODS.map((candidate) => {
      const checked = candidate === period

      return (
        <Box
          key={candidate}
          component="button"
          type="button"
          role="radio"
          aria-checked={checked}
          onClick={() => onChange(candidate)}
          sx={(theme) => ({
            appearance: 'none',
            border: 0,
            m: 0,
            font: 'inherit',
            cursor: 'pointer',
            height: 30,
            px: 1.75,
            borderRadius: 2,
            fontSize: 13,
            fontVariantNumeric: 'tabular-nums',
            bgcolor: checked ? theme.palette.veilleuse.raised : 'transparent',
            color: checked ? theme.palette.text.primary : theme.palette.veilleuse.caption,
          })}
        >
          {PERIOD_LABELS[candidate]}
        </Box>
      )
    })}
  </Box>
)
