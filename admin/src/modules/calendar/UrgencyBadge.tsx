import Box from '@mui/material/Box'
import ReportIcon from '@mui/icons-material/Report'
import KeyboardDoubleArrowUpIcon from '@mui/icons-material/KeyboardDoubleArrowUp'
import RemoveIcon from '@mui/icons-material/Remove'
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown'
import { urgencyColor } from '../../design/tokens'

/** The four urgencies the API computes (MAG-406), each with its word and its icon. */
export const URGENCIES: Record<string, { label: string; Icon: typeof ReportIcon }> = {
  critical: { label: 'critique', Icon: ReportIcon },
  high: { label: 'haute', Icon: KeyboardDoubleArrowUpIcon },
  normal: { label: 'normale', Icon: RemoveIcon },
  low: { label: 'basse', Icon: KeyboardArrowDownIcon },
}

interface UrgencyBadgeProps {
  /** `low`, `normal`, `high` or `critical`, as the API computes it. */
  urgency?: string | null
}

/**
 * A task's urgency: an icon and a word, never the colour alone (spec « Gestion
 * des tâches »); the colour is on the icon, the word keeps the text's contrast. Computed by the API, never entered — so nothing to click. An
 * urgency the page does not know draws nothing rather than a wrong word.
 */
export const UrgencyBadge = ({ urgency }: UrgencyBadgeProps) => {
  const known = urgency ? URGENCIES[urgency] : undefined
  if (!known) return null
  const { label, Icon } = known

  return (
    <Box
      component="span"
      data-testid="urgency-badge"
      data-urgency={urgency}
      title={`Urgence ${label}`}
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.25,
        color: 'text.secondary',
        fontSize: '0.7rem',
        fontWeight: 600,
        whiteSpace: 'nowrap',
      }}
    >
      <Icon aria-hidden fontSize="inherit" sx={{ color: urgencyColor(urgency as string) }} />
      <span>{label}</span>
    </Box>
  )
}
