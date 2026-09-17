import Box from '@mui/material/Box'
import { formatCents } from './accountTypes'

interface AmountProps {
  cents: number
  currency?: string
  /** Colour credits green and debits plain, the way a statement reads. */
  signed?: boolean
  bold?: boolean
}

/**
 * Money, set the way money should be: tabular figures, right-aligned, so
 * columns of amounts line up on the decimal and can be compared at a glance.
 */
export const Amount = ({ cents, currency = 'EUR', signed = false, bold = false }: AmountProps) => (
  <Box
    component="span"
    sx={{
      display: 'block',
      textAlign: 'right',
      fontVariantNumeric: 'tabular-nums',
      fontWeight: bold ? 600 : undefined,
      color: signed && cents > 0 ? 'success.main' : undefined,
    }}
  >
    {signed && cents > 0 ? '+' : ''}
    {formatCents(cents, currency)}
  </Box>
)
