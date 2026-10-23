import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import { Link as RouterLink } from 'react-router-dom'
import { TOKENS } from '../../design/tokens'
import { moduleColor } from './moduleColor'

interface HomeCardProps {
  module: string
  title: string
  /** The route that opens the module. */
  to: string
  children?: ReactNode
}

/** A card of the Accueil: the title, in its module's colour, opens the module; the body is the card's own. */
export const HomeCard = ({ module, title, to, children }: HomeCardProps) => (
  <Card component="section" aria-label={title} data-testid={`home-card-${module}`} sx={{ p: 2 }}>
    <Typography
      variant="subtitle1"
      component="h2"
      sx={{ fontWeight: TOKENS.typography.weight.semibold, mb: children ? 1 : 0 }}
    >
      <Box
        component={RouterLink}
        to={to}
        sx={(theme) => ({
          display: 'inline-flex',
          alignItems: 'center',
          color: moduleColor(theme, module),
          textDecoration: 'none',
          borderRadius: `${TOKENS.radius.xs}px`,
          '&:hover': { textDecoration: 'underline' },
          '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 },
        })}
      >
        {title}
        <ChevronRightIcon fontSize="small" aria-hidden />
      </Box>
    </Typography>
    {children}
  </Card>
)

interface HomeActionCardProps {
  module: string
  icon: ReactNode
  title: string
  /** When it happens: « ce soir », « 18 h »… */
  moment: string
  actionLabel: string
  onAction: () => void
}

/** The action variant: what the module asks to do now, on its colour at 12 %, with one pill button. */
export const HomeActionCard = ({ module, icon, title, moment, actionLabel, onAction }: HomeActionCardProps) => (
  <Card
    component="section"
    aria-label={title}
    data-testid={`home-action-${module}`}
    elevation={0}
    sx={(theme) => ({
      display: 'flex',
      alignItems: 'center',
      gap: 2,
      p: 2,
      backgroundColor: alpha(moduleColor(theme, module), 0.12),
      backgroundImage: 'none',
    })}
  >
    <Box sx={(theme) => ({ display: 'flex', color: moduleColor(theme, module) })}>{icon}</Box>
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography variant="subtitle2" component="h2">
        {title}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {moment}
      </Typography>
    </Box>
    <Button
      onClick={onAction}
      sx={(theme) => ({
        borderRadius: `${TOKENS.radius.pill}px`,
        textTransform: 'none',
        bgcolor: theme.palette.veilleuse.ink,
        color: theme.palette.veilleuse.onInk,
        '&:hover': { bgcolor: theme.palette.veilleuse.ink, opacity: 0.9 },
        [theme.breakpoints.down('md')]: { minHeight: 48 },
      })}
    >
      {actionLabel}
    </Button>
  </Card>
)
