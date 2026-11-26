import { useNavigate } from 'react-router-dom'
import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { HEALTH_CONFIGURATION_PATH } from '../../components/layout/navigation'
import { activeSessions, gapToGoal, hasNoStepSource, plannedSportSessions, spokenClock, stepProjection, weekBars } from './activitySeries'
import type { ActivityDay } from './useActivityDay'
import { useActivityDay } from './useActivityDay'
import { formatInteger } from './healthProfileApi'
import { cardSx, parisClock } from './nutritionBalance'
import { NONE } from './suiviSeries'
import type { Workout } from './workoutFormat'

const SPORT_PATH = '/workouts'
const caption = { fontSize: 12, color: (theme: Theme) => theme.palette.veilleuse.caption } as const

const activityCardSx = { ...cardSx, borderRadius: '22px', p: '14px 16px' } as const
const headerSx = { fontSize: 14, fontWeight: 400, color: 'text.secondary' } as const
const rowSx = (theme: Theme) => ({ display: 'flex', alignItems: 'center', borderTop: `1px solid ${alpha(theme.palette.text.primary, 0.06)}` })

const RING = 168
const STROKE = 14
const RADIUS = (RING - STROKE) / 2
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

const Arc = ({ share, color, label }: { share: number; color: string; label?: string }) => (
  <circle
    data-testid={label}
    cx={RING / 2}
    cy={RING / 2}
    r={RADIUS}
    fill="none"
    stroke={color}
    strokeWidth={STROKE}
    strokeLinecap="round"
    strokeDasharray={`${Math.max(0, Math.min(1, share)) * CIRCUMFERENCE} ${CIRCUMFERENCE}`}
    transform={`rotate(-90 ${RING / 2} ${RING / 2})`}
  />
)

const StepsCard = ({ day }: { day: ActivityDay }) => {
  const navigate = useNavigate()
  const { profile, week, today, now } = day
  const todaySteps = week.find((row) => row.date === today)?.steps ?? null
  const noSource = hasNoStepSource(profile, todaySteps)
  const goal = profile.stepGoal
  const steps = todaySteps ?? 0
  const { projected, endLabel } = stepProjection({ steps, now, windowStart: profile.suggestionWindowStart, windowEnd: profile.suggestionWindowEnd })
  const gap = gapToGoal(projected, goal)

  return (
    <Box component="section" aria-label="Pas du jour" sx={{ ...activityCardSx, p: '20px', flexDirection: 'row', alignItems: 'center', gap: '24px' }}>
      <Box sx={{ position: 'relative', width: RING, height: RING, flexShrink: 0 }} data-testid="activity-ring">
        <svg width={RING} height={RING} aria-hidden="true">
          <Box component="g" sx={(theme) => ({ color: theme.palette.veilleuse.track })}>
            <Arc share={1} color="currentColor" />
          </Box>
          {!noSource && projected !== null && goal > 0 && (
            <Box component="g" sx={(theme) => ({ color: alpha(theme.palette.module.sport, 0.3) })}>
              <Arc share={projected / goal} color="currentColor" label="ring-projection" />
            </Box>
          )}
          {!noSource && goal > 0 && (
            <Box component="g" sx={(theme) => ({ color: theme.palette.module.sport })}>
              <Arc share={steps / goal} color="currentColor" label="ring-done" />
            </Box>
          )}
        </svg>
        <Box sx={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
          <Typography component="span" data-testid="activity-steps" sx={{ fontSize: 30, fontWeight: 400, letterSpacing: '-0.02em' }}>
            {noSource ? NONE : formatInteger(steps)}
          </Typography>
          <Typography component="span" sx={caption}>
            / {formatInteger(goal)}
          </Typography>
        </Box>
      </Box>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75, minWidth: 0 }}>
        {noSource ? (
          <>
            <Typography sx={caption}>Aucune source de pas</Typography>
            <Button
              disableElevation
              variant="text"
              onClick={() => navigate(HEALTH_CONFIGURATION_PATH)}
              sx={(theme) => ({
                alignSelf: 'flex-start',
                height: 36,
                borderRadius: '999px',
                px: 2,
                fontSize: 14,
                bgcolor: alpha(theme.palette.module.sport, 0.16),
                color: theme.palette.module.sport,
              })}
            >
              Relier
            </Button>
          </>
        ) : (
          <>
            <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>Pas · aujourd’hui à {spokenClock(parisClock(now))}</Typography>
            <Typography data-testid="activity-projection" sx={{ fontSize: 15 }}>
              À ce rythme : {projected === null ? NONE : `~${formatInteger(projected)} / ${formatInteger(goal)} à ${endLabel}`}
            </Typography>
            {gap !== null && (
              <Typography data-testid="activity-gap" sx={caption}>
                Il manquerait ~{formatInteger(gap)} pas
              </Typography>
            )}
          </>
        )}
      </Box>
    </Box>
  )
}

const WeekCard = ({ day }: { day: ActivityDay }) => {
  const goal = day.profile.stepGoal
  const { bars, goalHeight } = weekBars(day.week, goal, day.today)

  return (
    <Box component="section" aria-label="7 jours" sx={{ ...activityCardSx, flexGrow: 1 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <Typography component="span" sx={headerSx}>
          7 jours
        </Typography>
        <Typography component="span" sx={caption}>
          objectif {formatInteger(goal)}
        </Typography>
      </Box>
      <Box sx={{ position: 'relative', height: 150, display: 'flex', alignItems: 'flex-end', gap: 1 }}>
        <Box
          data-testid="week-goal-line"
          sx={(theme) => ({ position: 'absolute', left: 0, right: 0, bottom: `${goalHeight}%`, borderTop: `1px dashed ${alpha(theme.palette.text.primary, 0.22)}` })}
        />
        {bars.map((bar) => (
          <Box
            key={bar.date}
            data-testid="week-bar"
            aria-label={`${bar.label} : ${bar.steps === null ? 'pas de donnée' : `${formatInteger(bar.steps)} pas`}`}
            sx={{ flex: '1 1 0', minWidth: 0, height: '100%', display: 'flex', alignItems: 'flex-end' }}
          >
            <Box
              sx={(theme) => ({
                width: '100%',
                height: `${bar.height.toFixed(1)}%`,
                borderRadius: '3px 3px 1px 1px',
                bgcolor: bar.today ? theme.palette.module.sport : alpha(theme.palette.module.sport, bar.atGoal ? 0.55 : 0.28),
              })}
            />
          </Box>
        ))}
      </Box>
      <Box sx={{ display: 'flex', gap: 1 }}>
        {bars.map((bar) => (
          <Typography key={bar.date} component="span" sx={{ ...caption, fontSize: 11, flex: '1 1 0', textAlign: 'center', fontWeight: 400 }}>
            {bar.label}
          </Typography>
        ))}
      </Box>
    </Box>
  )
}

const kcalLabel = (workout: Workout): string | null => {
  if (workout.kcal === null || workout.kcal === undefined) return null

  return `${workout.source === 'planned' ? '~' : ''}${formatInteger(workout.kcal)} kcal`
}

const SessionRow = ({ workout }: { workout: Workout }) => {
  const planned = workout.source === 'planned'
  const kcal = kcalLabel(workout)

  return (
    <Box data-testid="activity-session" sx={(theme) => ({ ...rowSx(theme), minHeight: 56, gap: 1.5, opacity: planned ? 0.8 : 1 })}>
      <Typography component="span" sx={{ width: 46, flexShrink: 0, fontSize: 13, color: 'text.secondary' }}>
        {parisClock(new Date(workout.startedAt))}
      </Typography>
      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Typography component="span" sx={{ fontSize: 15 }}>
          {workout.title}
        </Typography>
        <Typography component="span" sx={caption}>
          {workout.durationMinutes} min
        </Typography>
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 0.5 }}>
        {kcal && (
          <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
            {kcal}
          </Typography>
        )}
        <Box
          component="span"
          data-testid="activity-session-state"
          sx={(theme) => ({
            display: 'inline-flex',
            alignItems: 'center',
            height: 22,
            px: 1,
            borderRadius: '999px',
            fontSize: 11,
            bgcolor: alpha(theme.palette.text.primary, 0.06),
            color: planned ? theme.palette.text.secondary : theme.palette.success.main,
          })}
        >
          {planned ? 'Prévue' : 'Fait'}
        </Box>
      </Box>
    </Box>
  )
}

const SessionsCard = ({ day }: { day: ActivityDay }) => {
  const navigate = useNavigate()
  const sessions = activeSessions(day.workouts)
  const cited = plannedSportSessions(day.workouts)
  const done = sessions.filter((w) => w.source !== 'planned').length
  const planned = sessions.length - done
  const header = [done > 0 && `${done} ${done > 1 ? 'faites' : 'faite'}`, planned > 0 && `${planned} ${planned > 1 ? 'prévues' : 'prévue'}`].filter(Boolean).join(' · ')

  return (
    <Box component="section" aria-label="Sessions actives" sx={{ ...activityCardSx, pb: '4px' }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <Typography component="span" sx={headerSx}>
          Sessions actives
        </Typography>
        {header && (
          <Typography component="span" sx={caption}>
            {header}
          </Typography>
        )}
      </Box>
      {sessions.length === 0 && cited.length === 0 && <Typography sx={caption}>Aucune session aujourd’hui</Typography>}
      {sessions.map((workout) => (
        <SessionRow key={workout.id} workout={workout} />
      ))}
      {cited.map((workout) => (
        <Box key={workout.id} data-testid="activity-sport-session" sx={(theme) => ({ ...rowSx(theme), minHeight: 44, gap: 1.5 })}>
          <Typography component="span" sx={{ ...caption, fontSize: 13, width: 46, flexShrink: 0 }}>
            {parisClock(new Date(workout.startedAt))}
          </Typography>
          <Typography component="span" sx={{ ...caption, fontSize: 13, flex: 1, minWidth: 0 }}>
            Séance prévue · {workout.title}
          </Typography>
          <Button variant="text" size="small" onClick={() => navigate(SPORT_PATH)} sx={(theme) => ({ color: theme.palette.module.sport, fontSize: 13 })}>
            Sport ›
          </Button>
        </Box>
      ))}
    </Box>
  )
}

/** Santé › Activité (MAG-897): the day's steps against the goal with their projection, the active sessions, the week. */
export const ActivityPage = () => {
  const { state, loading, error, retry } = useActivityDay()

  if (state === null) {
    return (
      <Box sx={{ py: 2, display: 'flex', alignItems: 'center', gap: 1.5 }}>
        {loading && <Typography sx={caption}>Chargement…</Typography>}
        {!loading && error && (
          <>
            <Typography sx={caption}>Erreur : l’activité n’a pas pu être chargée.</Typography>
            <Button size="small" onClick={() => void retry()}>
              Réessayer
            </Button>
          </>
        )}
      </Box>
    )
  }

  return (
    <Box data-testid="activity-board" sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.75, py: 1, alignItems: 'stretch' }}>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.75, minWidth: 0 }}>
        <StepsCard day={state} />
        <WeekCard day={state} />
      </Box>
      <Box data-testid="activity-sessions-column" sx={{ pb: '76px', minWidth: 0 }}>
        <SessionsCard day={state} />
      </Box>
    </Box>
  )
}
