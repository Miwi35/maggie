import Badge from '@mui/material/Badge'
import ButtonBase from '@mui/material/ButtonBase'
import { alpha } from '@mui/material/styles'
import { TOKENS } from '../../design/tokens'
import { MaggieAvatar } from './MaggieAvatar'

export const PORTRAIT_SIZE = 64
export const PORTRAIT_MARGIN = 24

interface MaggiePortraitProps {
  unread: boolean
  /** The server's count, when known: shown instead of the dot. */
  count?: number
  onClick: () => void
}

/**
 * Maggie, folded away: her face, 64 px, bottom right. The admin opens with this
 * and nothing else of her; a click brings the conversation and the portrait goes
 * away — closing the chat (✕) brings it back (MAG-624, mockups `Admin-Cible` /
 * `Chat-Tablette`). The badge is the charter's accent with a dark digit and a ring
 * of the background colour (mockup `Portrait-Pastille-Tablette`, MAG-966). The layout only renders it while the chat is closed.
 */
export const MaggiePortrait = ({ unread, count = 0, onClick }: MaggiePortraitProps) => (
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
    <Badge
      variant={count > 0 ? 'standard' : 'dot'}
      badgeContent={count > 0 ? count : undefined}
      max={99}
      invisible={!unread}
      sx={(theme) => ({
        '& .MuiBadge-badge': {
          top: 2,
          right: 2,
          minWidth: 24,
          height: 24,
          padding: '0 7px',
          borderRadius: 999,
          backgroundColor: TOKENS.brand.primary,
          color: TOKENS.brand.onPrimary,
          fontSize: 13,
          fontWeight: 600,
          fontVariantNumeric: 'tabular-nums',
          boxShadow: `0 0 0 3px ${theme.palette.background.default}`,
        },
        '& .MuiBadge-dot': { minWidth: 12, height: 12, padding: 0 },
      })}>
      <MaggieAvatar size={PORTRAIT_SIZE} />
    </Badge>
  </ButtonBase>
)
