import Badge from '@mui/material/Badge'
import ButtonBase from '@mui/material/ButtonBase'
import { alpha } from '@mui/material/styles'
import { MaggieAvatar } from './MaggieAvatar'

export const PORTRAIT_SIZE = 64
export const PORTRAIT_MARGIN = 24

interface MaggiePortraitProps {
  unread: boolean
  onClick: () => void
}

/**
 * Maggie, folded away: her face, 64 px, bottom right. The admin opens with this
 * and nothing else of her; a click brings the conversation and the portrait goes
 * away — closing the chat (✕) brings it back (MAG-624, mockups `Admin-Cible` /
 * `Chat-Tablette`). The layout only renders it while the chat is closed.
 */
export const MaggiePortrait = ({ unread, onClick }: MaggiePortraitProps) => (
  <ButtonBase
    data-testid="maggie-portrait"
    aria-label="Ouvrir la conversation avec Maggie"
    onClick={onClick}
    sx={(theme) => ({
      position: 'fixed',
      right: PORTRAIT_MARGIN,
      bottom: PORTRAIT_MARGIN,
      width: PORTRAIT_SIZE,
      height: PORTRAIT_SIZE,
      borderRadius: '50%',
      zIndex: theme.zIndex.speedDial,
      boxShadow: `0 4px 16px ${alpha(theme.palette.common.black, 0.28)}`,
      '&:focus-visible': { outline: `3px solid ${theme.palette.primary.main}`, outlineOffset: 2 },
    })}
  >
    <Badge variant="dot" color="error" invisible={!unread} sx={{ '& .MuiBadge-badge': { top: 6, right: 6 } }}>
      <MaggieAvatar size={PORTRAIT_SIZE} />
    </Badge>
  </ButtonBase>
)
