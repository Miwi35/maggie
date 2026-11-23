import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import BedtimeOutlinedIcon from '@mui/icons-material/BedtimeOutlined'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { Link as RouterLink } from 'react-router-dom'

interface MomentCardProps {
  icon: 'play' | 'moon'
  title: string
  moment: string
  to: string
  openLabel: string
  /** What a screen reader says of the pill; defaults to its text. */
  ariaLabel?: string
}

/** The card under a moment message of the chat (digest, bilan): a disc, a title, its moment and the « Ouvrir » pill. */
export const MomentCard = ({ icon, title, moment, to, openLabel, ariaLabel }: MomentCardProps) => (
  <Box
    data-testid="moment-card"
    sx={(theme) => ({
      mt: 1,
      width: '88%',
      display: 'flex',
      alignItems: 'center',
      gap: 1.5,
      p: 1.25,
      borderRadius: '16px',
      background: `linear-gradient(160deg, ${alpha(theme.palette.primary.main, 0.16)}, ${theme.palette.background.paper})`,
      border: `1px solid ${alpha(theme.palette.primary.main, 0.22)}`,
    })}
  >
    <Box
      aria-hidden="true"
      sx={(theme) => ({
        width: 36,
        height: 36,
        flexShrink: 0,
        borderRadius: '50%',
        display: 'grid',
        placeItems: 'center',
        ...(icon === 'play'
          ? { bgcolor: 'primary.main', color: theme.palette.primary.contrastText }
          : { bgcolor: alpha(theme.palette.primary.main, 0.18), color: 'primary.light' }),
      })}
    >
      {icon === 'play' ? <PlayArrowIcon fontSize="small" /> : <BedtimeOutlinedIcon fontSize="small" />}
    </Box>
    <Box sx={{ minWidth: 0, flex: 1 }}>
      <Typography sx={{ fontSize: 14, fontWeight: 500 }}>{title}</Typography>
      <Typography noWrap sx={{ fontSize: 12, color: 'text.secondary' }}>
        {moment}
      </Typography>
    </Box>
    <Link
      component={RouterLink}
      to={to}
      aria-label={ariaLabel}
      underline="none"
      sx={(theme) => ({
        flexShrink: 0,
        minHeight: 34,
        px: 1.75,
        display: 'inline-flex',
        alignItems: 'center',
        borderRadius: '17px',
        fontSize: 13,
        fontWeight: 500,
        color: 'primary.light',
        border: `1px solid ${alpha(theme.palette.primary.light, 0.5)}`,
      })}
    >
      {openLabel}
    </Link>
  </Box>
)
