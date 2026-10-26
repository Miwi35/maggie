import Badge from '@mui/material/Badge'
import ButtonBase from '@mui/material/ButtonBase'
import { alpha } from '@mui/material/styles'
import { MaggieAvatar } from './MaggieAvatar'

export const PORTRAIT_SIZE = 64
export const PORTRAIT_MARGIN = 24

interface MaggiePortraitProps {
  /** The conversation is out: the click folds it away instead of opening it. */
  chatOpen: boolean
  unread: boolean
  /** Distance from the right edge, in px — clears the chat column when it sits beside the page. */
  right: number
  /** Distance from the bottom edge, in px. */
  bottom?: number
  onClick: () => void
}

/**
 * Maggie, folded away: her face, 64 px, bottom right. The admin opens with this
 * and nothing else of her; a click brings the conversation, another puts it
 * back (MAG-624, mockups `Admin-Cible` / `Chat-Tablette`).
 */
export const MaggiePortrait = ({ chatOpen, unread, right, bottom = PORTRAIT_MARGIN, onClick }: MaggiePortraitProps) => (
  <ButtonBase
    data-testid="maggie-portrait"
    aria-label={chatOpen ? 'Replier la conversation avec Maggie' : 'Ouvrir la conversation avec Maggie'}
    aria-expanded={chatOpen}
    onClick={onClick}
    sx={(theme) => ({
      position: 'fixed',
      right,
      bottom,
      width: PORTRAIT_SIZE,
      height: PORTRAIT_SIZE,
      borderRadius: '50%',
      zIndex: theme.zIndex.speedDial,
      boxShadow: `0 4px 16px ${alpha(theme.palette.common.black, 0.28)}`,
      transition: 'right 225ms cubic-bezier(0, 0, 0.2, 1)',
      '&:focus-visible': { outline: `3px solid ${theme.palette.primary.main}`, outlineOffset: 2 },
      '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
    })}
  >
    <Badge
      variant="dot"
      color="error"
      invisible={!unread || chatOpen}
      sx={{ '& .MuiBadge-badge': { top: 6, right: 6 } }}
    >
      <MaggieAvatar size={PORTRAIT_SIZE} />
    </Badge>
  </ButtonBase>
)
