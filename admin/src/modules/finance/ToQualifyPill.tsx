import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import { Link } from 'react-admin'
import { formatCents } from './accountTypes'
import { reviewLink } from './reviewFilter'
import { useToQualifySummary } from './useToQualifySummary'

/** « À qualifier · 3 · 57,99 € »: the lines no envelope counts yet, one tap from the page that qualifies them. */
export const ToQualifyPill = () => {
  const summary = useToQualifySummary()

  if (summary === null || summary.count < 1) return null

  return (
    <Box sx={{ display: 'flex' }}>
      <Chip
        component={Link}
        to={reviewLink({ state: 'unrecognised', period: 'all' })}
        clickable
        color="warning"
        variant="outlined"
        label={`À qualifier · ${summary.count} · ${formatCents(summary.cents)}`}
      />
    </Box>
  )
}
