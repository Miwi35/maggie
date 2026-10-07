import Box from '@mui/material/Box'
import { alpha, keyframes } from '@mui/material/styles'

const popIn = keyframes`
  0% { transform: scale(0.4); opacity: 0; }
  60% { transform: scale(1.08); opacity: 1; }
  100% { transform: scale(1); opacity: 1; }
`
const breathe = keyframes`
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.035); }
`
const halo = keyframes`
  0% { transform: scale(1); opacity: 0.55; }
  100% { transform: scale(1.6); opacity: 0; }
`

interface MaggieAvatarProps {
  /** Diameter in px: ~96 for the interruption, ~36 in the chat header. */
  size: number
  /** Pop-in spring, then breathing with a pulsing halo. Off for a still avatar. */
  animated?: boolean
}

/**
 * Maggie's face — `public/maggie.png` on the board's violet gradient, framed
 * at `object-position: 50% 12%` and zoomed 1.2 (MAG-311). The one avatar of
 * the interruption and of the chat. Animation is dropped under
 * `prefers-reduced-motion`.
 */
export const MaggieAvatar = ({ size, animated = false }: MaggieAvatarProps) => (
  <Box
    data-testid="maggie-avatar"
    sx={(theme) => ({
      position: 'relative',
      width: size,
      height: size,
      flexShrink: 0,
      borderRadius: '50%',
      background: `linear-gradient(145deg, ${theme.palette.maggie.avatarFrom}, ${theme.palette.maggie.avatarTo})`,
      ...(animated && {
        animation: `${popIn} 760ms cubic-bezier(0.2, 0.9, 0.3, 1.2) both, ${breathe} 4.8s ease-in-out 760ms infinite`,
        '&::after': {
          content: '""',
          position: 'absolute',
          inset: 0,
          borderRadius: '50%',
          boxShadow: `0 0 0 2px ${alpha(theme.palette.maggie.avatarFrom, 0.6)}`,
          animation: `${halo} 2.4s ease-out 760ms infinite`,
          pointerEvents: 'none',
        },
        '@media (prefers-reduced-motion: reduce)': {
          animation: 'none',
          '&::after': { animation: 'none', display: 'none' },
        },
      }),
    })}
  >
    <Box sx={{ width: '100%', height: '100%', borderRadius: '50%', overflow: 'hidden' }}>
      <Box
        component="img"
        src="/admin/maggie.png"
        alt=""
        sx={{
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          objectPosition: '50% 12%',
          transform: 'scale(1.2)',
          display: 'block',
        }}
      />
    </Box>
  </Box>
)
