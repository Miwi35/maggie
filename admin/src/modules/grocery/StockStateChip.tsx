import Chip from '@mui/material/Chip'
import { STOCK_STATE_CHOICES, STOCK_STATE_COLORS, type StockState } from './productChoices'

export const StockStateChip = ({ state }: { state?: string | null }) => {
  const known = STOCK_STATE_CHOICES.find((c) => c.id === state) ?? STOCK_STATE_CHOICES[0]

  return <Chip label={known.name} color={STOCK_STATE_COLORS[known.id as StockState]} size="small" />
}
