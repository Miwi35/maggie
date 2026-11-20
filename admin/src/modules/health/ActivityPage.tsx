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
    <Box component="section" aria-label="Pas du jour" sx={cardSx}>
      <Typography sx={{ fontSize: 15, fontWeight: 500 }}>Pas du jour</Typography>
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 1, py: 1 }}>
        <Box sx={{ position: 'relative', width: RING, height: RING }} data-testid="activity-ring">
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
            <Typography component="span" data-testid="activity-steps" sx={{ fontSize: 30, fontWeight: 300 }}>
              {noSource ? NONE : formatInteger(steps)}
            </Typography>
            <Typography component="span" sx={caption}>
              / {formatInteger(goal)}
            </Typography>
          </Box>
        </Box>
        {noSource ? (
          <>
            <Typography sx={caption}>Aucune source de pas</Typography>
            <Button size="small" variant="outlined" onClick={() => navigate(HEALTH_CONFIGURATION_PATH)}>
              Relier
            </Button>
          </>
        ) : (
          <>
            <Typography sx={caption}>Pas · aujourd’hui à {spokenClock(parisClock(now))}</Typography>
            <Typography data-testid="activity-projection" sx={{ fontSize: 14 }}>
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
    <Box component="section" aria-label="7 jours" sx={cardSx}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 15, fontWeight: 500 }}>
        <span>7 jours</span>
        <Typography component="span" sx={{ ...caption, fontWeight: 400 }}>
          objectif {formatInteger(goal)}
        </Typography>
      </Box>
      <Box sx={{ position: 'relative', height: 140, display: 'flex', alignItems: 'flex-end', gap: 1 }}>
        <Box
          data-testid="week-goal-line"
          sx={(theme) => ({ position: 'absolute', left: 0, right: 0, bottom: `${goalHeight}%`, borderTop: `1px dashed ${theme.palette.veilleuse.caption}` })}
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
                bgcolor: bar.today || bar.atGoal ? theme.palette.module.sport : alpha(theme.palette.module.sport, 0.45),
                opacity: bar.today || bar.atGoal ? 1 : 0.9,
              })}
            />
          </Box>
        ))}
      </Box>
      <Box sx={{ display: 'flex', gap: 1 }}>
        {bars.map((bar) => (
          <Typography key={bar.date} component="span" sx={{ ...caption, flex: '1 1 0', textAlign: 'center', fontWeight: bar.today ? 600 : 400 }}>
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
    <Box data-testid="activity-session" sx={{ display: 'flex', gap: 1.5, alignItems: 'baseline', opacity: planned ? 0.65 : 1 }}>
      <Typography component="span" sx={caption}>
        {parisClock(new Date(workout.startedAt))}
      </Typography>
      <Typography component="span" sx={{ flex: 1, fontSize: 14, minWidth: 0 }}>
        {workout.title}
      </Typography>
      <Typography component="span" sx={caption}>
        {workout.durationMinutes} min
      </Typography>
      {kcal && (
        <Typography component="span" sx={caption}>
          {kcal}
        </Typography>
      )}
      <Typography component="span" sx={{ fontSize: 12, fontWeight: 500 }}>
        {planned ? 'Prévue' : 'Fait'}
      </Typography>
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
    <Box component="section" aria-label="Sessions actives" sx={cardSx}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 15, fontWeight: 500 }}>
        <span>Sessions actives</span>
        {header && (
          <Typography component="span" sx={{ ...caption, fontWeight: 400 }}>
            {header}
          </Typography>
        )}
      </Box>
      {sessions.length === 0 && cited.length === 0 && <Typography sx={caption}>Aucune session aujourd’hui</Typography>}
      {sessions.map((workout) => (
        <SessionRow key={workout.id} workout={workout} />
      ))}
      {cited.map((workout) => (
        <Box key={workout.id} data-testid="activity-sport-session" sx={{ display: 'flex', gap: 1.5, alignItems: 'baseline' }}>
          <Typography component="span" sx={caption}>
            {parisClock(new Date(workout.startedAt))}
          </Typography>
          <Typography component="span" sx={{ flex: 1, fontSize: 14 }}>
            Séance prévue · {workout.title}
          </Typography>
          <Button size="small" onClick={() => navigate(SPORT_PATH)}>
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
    <Box data-testid="activity-board" sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.75, py: 1, alignItems: 'start' }}>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.75, minWidth: 0 }}>
        <StepsCard day={state} />
        <WeekCard day={state} />
      </Box>
      <SessionsCard day={state} />
    </Box>
  )
}
