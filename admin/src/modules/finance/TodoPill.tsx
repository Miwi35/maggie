import Chip from '@mui/material/Chip'
import { Link as RouterLink } from 'react-router-dom'
import { tileInnerLinkSx } from './DashboardTile'

interface TodoPillProps {
  label: string
  /** The screen the pill opens; without it the pill is only shown. */
  to?: string
  testId: string
}

/** A pill of a tile: what waits, one tap from the screen that settles it. Above the tile's stretched link. */
export const TodoPill = ({ label, to, testId }: TodoPillProps) =>
  to ? (
    <Chip
      component={RouterLink}
      to={to}
      clickable
      size="small"
      color="warning"
      variant="outlined"
      label={label}
      data-testid={testId}
      sx={tileInnerLinkSx}
    />
  ) : (
    <Chip size="small" variant="outlined" label={label} data-testid={testId} />
  )
