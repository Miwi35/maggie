import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { Link as RouterLink, useLocation } from 'react-router-dom'
import { TOKENS } from '../../design/tokens'
import { REVIEW_ACTIVITY, REVIEW_ACTIVITY_PATH } from '../finance/reviewActivity'
import type { ReviewLaunchState } from '../finance/reviewActivity'
import { moduleColor } from './moduleColor'
import type { HomeDay } from './homeDay'

/** The visible pill is 28 px; the link around it reaches this height, so a finger or a pointer still hits it. */
const TOUCH_TARGET = 44

/** Where an agenda event that is an activity opens, and the module whose colour its ▶ takes. */
export interface LaunchTarget {
  module: string
  path: string
}

/**
 * The activity an agenda event launches, from its `activity` field; `null` for an event that is not
 * one. Cuisine and Sport have no activity yet: their meal and workout lines open their own sheet,
 * which the card that draws the line passes as `to`.
 */
export const launchTargetFor = (activity: string | null | undefined): LaunchTarget | null =>
  activity === REVIEW_ACTIVITY ? { module: 'finance', path: REVIEW_ACTIVITY_PATH } : null

interface ActivityLaunchButtonProps {
  module: string
  /** What is launched, as the screen reader says it: « Lancer <activity> ». */
  activity: string
  /** The Accueil's day: the ▶ only exists for today. */
  day: HomeDay
  /** The route the activity (or, until it exists, the meal's or workout's sheet) opens at. */
  to: string
}

/**
 * The discreet « ▶ Lancer » on an agenda line or a module card of the Accueil: a 28 px pill on the
 * module's colour, a thin outline, 13 px text. Nothing for tomorrow. Quitting the activity comes
 * back to the Accueil as it was.
 */
export const ActivityLaunchButton = ({ module, activity, day, to }: ActivityLaunchButtonProps) => {
  const location = useLocation()
  if (day !== 'today') return null

  const state: ReviewLaunchState = { from: `${location.pathname}${location.search}` }

  return (
    <Box
      component={RouterLink}
      to={to}
      state={state}
      aria-label={`Lancer ${activity}`}
      data-testid={`home-launch-${module}`}
      sx={(theme) => {
        const color = moduleColor(theme, module)

        return {
          position: 'relative',
          display: 'inline-flex',
          alignItems: 'center',
          flexShrink: 0,
          minHeight: TOUCH_TARGET,
          textDecoration: 'none',
          '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: -2, borderRadius: `${TOKENS.radius.pill}px` },
          '& .pill': {
            display: 'inline-flex',
            alignItems: 'center',
            gap: 0.5,
            height: 28,
            px: 1.25,
            borderRadius: `${TOKENS.radius.pill}px`,
            border: `1px solid ${alpha(color, 0.4)}`,
            backgroundColor: alpha(color, 0.12),
            color,
            fontSize: 13,
            fontWeight: TOKENS.typography.weight.medium,
            lineHeight: 1,
          },
          '&:hover .pill': { backgroundColor: alpha(color, 0.2) },
        }
      }}
    >
      <span className="pill" aria-hidden>
        <PlayArrowIcon sx={{ fontSize: 14 }} />
        Lancer
      </span>
    </Box>
  )
}
