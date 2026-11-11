import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import LinearProgress from '@mui/material/LinearProgress'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { HEALTH_SUIVI_PATH } from '../../../components/layout/navigation'
import { formatInteger } from '../../health/healthProfileApi'
import { formatDuration } from '../../health/workoutFormat'
import { HomeCard } from '../HomeCard'
import { HomeCardError } from '../HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../homeCardStatus'
import { moduleColor } from '../moduleColor'
import type { HomeDay } from '../homeDay'
import { useHealthCardState } from '../useHealthCardState'
import { isHealthEmpty } from './healthCardState'
import type { HealthBalance, HealthFast, HealthHomeState, HealthSteps, HealthWorkout } from './healthCardState'

/** The « Santé » card of the Accueil (MAG-566): the planned session, steps, calorie balance and fasting window; no verdict. */
export const HealthCard = ({ day }: { day: HomeDay }) => {
  const { state, loading, error, retry } = useHealthCardState(day)
  const status = homeCardStatus({ state, loading, error }, isHealthEmpty)
  useReportHomeCard('health', status)

  if (status === 'empty') return null

  return (
    <HomeCard module="health" title="Santé" to={HEALTH_SUIVI_PATH}>
      {state && !isHealthEmpty(state) ? (
        <Body state={state} />
      ) : !error && loading ? (
        <Box aria-busy="true" data-testid="home-health-loading">
          <Skeleton width="60%" />
          <Skeleton width="80%" />
        </Box>
      ) : null}
      {error && <HomeCardError onRetry={() => void retry()} />}
    </HomeCard>
  )
}

const Body = ({ state }: { state: HealthHomeState }) => (
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
    {state.workout && <Workout workout={state.workout} />}
    {state.steps && <Steps steps={state.steps} />}
    {state.balance && <Balance balance={state.balance} day={state.day} />}
    {state.fast && <Fast fast={state.fast} day={state.day} />}
  </Box>
)

const Workout = ({ workout }: { workout: HealthWorkout }) => (
  <Typography variant="body2" data-testid="home-health-workout">
    {`${workout.title} · ${workout.time} · ${formatDuration(workout.durationMinutes)}`}
  </Typography>
)

const Bar = ({ value, label }: { value: number; label: string }) => (
  <LinearProgress
    variant="determinate"
    value={Math.min(100, Math.max(0, value))}
    aria-label={label}
    sx={(theme) => ({ height: 6, borderRadius: 3, color: moduleColor(theme, 'health'), '& .MuiLinearProgress-bar': { backgroundColor: 'currentColor' } })}
  />
)

const Steps = ({ steps }: { steps: HealthSteps }) => (
  <Box data-testid="home-health-steps">
    <Typography variant="body2">{`Pas · ${formatInteger(steps.count)} · objectif ${formatInteger(steps.goal)}`}</Typography>
    <Bar value={(steps.count / steps.goal) * 100} label="Pas du jour" />
  </Box>
)

const Balance = ({ balance, day }: { balance: HealthBalance; day: HomeDay }) => {
  const need = balance.needKcal === null ? '' : ` · besoin ${formatInteger(balance.needKcal)} kcal`
  const text =
    day === 'today'
      ? `Bilan du jour · ${formatInteger(balance.kcal)} mangées${need}`
      : `Calories projetées · ${formatInteger(balance.kcal)} kcal${balance.needKcal === null ? '' : ` · besoin ${formatInteger(balance.needKcal)} kcal`}`

  return (
    <Box data-testid="home-health-balance">
      <Typography variant="body2">{text}</Typography>
      {balance.needKcal !== null && balance.needKcal > 0 && <Bar value={(balance.kcal / balance.needKcal) * 100} label="Bilan calorique" />}
    </Box>
  )
}

const Fast = ({ fast, day }: { fast: HealthFast; day: HomeDay }) => {
  let text: string
  if (day === 'tomorrow') text = `Fenêtre prévue · ${fast.windowStart} – ${fast.windowEnd}`
  else if (fast.state === 'closed') text = 'Fenêtre fermée'
  else if (fast.state === 'open')
    text = `Fenêtre ouverte · ferme à ${fast.windowEnd}${fast.minutesToEdge === null ? '' : ` · ${formatDuration(fast.minutesToEdge)}`}`
  else text = `Jeûne en cours · ouvre à ${fast.windowStart}${fast.minutesToEdge === null ? '' : ` · ${formatDuration(fast.minutesToEdge)}`}`

  const ring = day === 'today' && fast.minutesToEdge !== null ? ringValue(fast) : null

  return (
    <Box data-testid="home-health-fast" sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
      {ring !== null && (
        <CircularProgress
          variant="determinate"
          value={ring}
          size={28}
          thickness={5}
          aria-label="Fenêtre de jeûne"
          sx={(theme) => ({ color: moduleColor(theme, 'health'), flexShrink: 0 })}
        />
      )}
      <Typography variant="body2">{text}</Typography>
    </Box>
  )
}

/** Share of the window left to its edge, over the window's length: a ring that empties as the edge nears. */
function ringValue(fast: HealthFast): number {
  const [sh, sm] = fast.windowStart.split(':').map(Number)
  const [eh, em] = fast.windowEnd.split(':').map(Number)
  let length = eh * 60 + em - (sh * 60 + sm)
  if (length <= 0) length += 24 * 60

  return Math.min(100, ((fast.minutesToEdge ?? 0) / length) * 100)
}
