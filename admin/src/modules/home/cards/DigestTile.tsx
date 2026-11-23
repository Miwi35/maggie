import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { Link as RouterLink } from 'react-router-dom'
import { MaggieAvatar } from '../../../components/maggie/MaggieAvatar'
import { digestMoment } from '../../maggie/digest/digestApi'
import { useReportHomeCard } from '../homeCardStatus'
import type { HomeDay } from '../homeDay'
import { useDigestTile } from '../useDigestTile'

const spoken = (day: string, durationMs: number): string => {
  const [y, m, d] = day.split('-').map(Number)
  const date = new Date(y, m - 1, d).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })
  const seconds = Math.floor(durationMs / 1000)
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  const length = [minutes > 0 && `${minutes} minute${minutes > 1 ? 's' : ''}`, rest > 0 && `${rest}`].filter(Boolean).join(' ')

  return `Digest du matin, ${date}${length ? `, ${length}` : ''} : écouter`
}

/** The tile of the morning digest at the head of the Accueil (MAG-960): visible until it is opened or expires. */
export const DigestTile = ({ day }: { day: HomeDay }) => {
  const tile = useDigestTile(day)
  useReportHomeCard('digest', tile ? 'filled' : 'empty')

  if (!tile) return null

  return (
    <Link
      component={RouterLink}
      to={`/digest?message=${encodeURIComponent(tile.id)}`}
      aria-label={spoken(tile.day, tile.durationMs)}
      underline="none"
      color="inherit"
      data-testid="home-digest-tile"
      sx={(theme) => ({
        display: 'flex',
        alignItems: 'center',
        gap: 1.75,
        minHeight: 68,
        px: 2,
        py: 1.25,
        borderRadius: '22px',
        background: `linear-gradient(160deg, ${alpha(theme.palette.primary.main, 0.2)}, ${theme.palette.background.paper})`,
        border: `1px solid ${alpha(theme.palette.primary.main, 0.22)}`,
      })}
    >
      <Box sx={{ flexShrink: 0, display: 'flex' }}>
        <MaggieAvatar size={44} />
      </Box>
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Typography sx={{ fontSize: 15, fontWeight: 500 }}>Digest du matin</Typography>
        <Typography noWrap sx={{ fontSize: 13, color: 'text.secondary' }}>
          {digestMoment(tile.day, tile.durationMs, 'long')}
        </Typography>
      </Box>
      <Box
        aria-hidden="true"
        sx={(theme) => ({
          flexShrink: 0,
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.5,
          px: 1.75,
          minHeight: 34,
          borderRadius: '17px',
          fontSize: 13,
          fontWeight: 500,
          color: 'primary.light',
          border: `1px solid ${alpha(theme.palette.primary.light, 0.5)}`,
        })}
      >
        <PlayArrowIcon sx={{ fontSize: 16 }} />
        Écouter
      </Box>
    </Link>
  )
}
