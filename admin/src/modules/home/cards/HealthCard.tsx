import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
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
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.25 }}>
    {[
      state.workout && <Workout key="workout" workout={state.workout} />,
      state.steps && <Steps key="steps" steps={state.steps} />,
      state.balance && <Balance key="balance" balance={state.balance} day={state.day} />,
      state.fast && <Fast key="fast" fast={state.fast} day={state.day} />,
    ]
      .filter(Boolean)
      .flatMap((row, index) => (index === 0 ? [row] : [<Divider key={`d${index}`} />, row]))}
  </Box>
)

const Line = ({ label, value, fraction, testId }: { label: string; value: string; fraction: number | null; testId: string }) => (
  <Box data-testid={testId} sx={{ display: 'flex', flexDirection: 'column', gap: 0.75 }}>
    <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1.5 }}>
      <Typography variant="body2">{label}</Typography>
      {value && (
        <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'right' }}>
          {value}
        </Typography>
      )}
    </Box>
    {fraction !== null && <Bar value={fraction * 100} label={label} />}
  </Box>
)

const Bar = ({ value, label }: { value: number; label: string }) => (
  <LinearProgress
    variant="determinate"
    value={Math.min(100, Math.max(0, value))}
    aria-label={label}
    sx={(theme) => ({ height: 6, borderRadius: 3, color: moduleColor(theme, 'health'), '& .MuiLinearProgress-bar': { backgroundColor: 'currentColor' } })}
  />
)

const Workout = ({ workout }: { workout: HealthWorkout }) => (
  <Line label={workout.title} value={`${workout.time} · ${formatDuration(workout.durationMinutes)}`} fraction={null} testId="home-health-workout" />
)

const Steps = ({ steps }: { steps: HealthSteps }) => (
  <Line
    label="Pas"
    value={`${formatInteger(steps.count)} · objectif ${formatInteger(steps.goal)}`}
    fraction={steps.count / steps.goal}
    testId="home-health-steps"
  />
)

const Balance = ({ balance, day }: { balance: HealthBalance; day: HomeDay }) => {
  const need = balance.needKcal === null ? '' : ` · besoin ${formatInteger(balance.needKcal)} kcal`

  return (
    <Line
      label={day === 'today' ? 'Bilan du jour' : 'Calories projetées'}
      value={day === 'today' ? `${formatInteger(balance.kcal)} mangées${need}` : `${formatInteger(balance.kcal)} kcal${need}`}
      fraction={balance.needKcal !== null && balance.needKcal > 0 ? balance.kcal / balance.needKcal : null}
      testId="home-health-balance"
    />
  )
}

const Fast = ({ fast, day }: { fast: HealthFast; day: HomeDay }) => {
  const left = fast.minutesToEdge === null ? '' : ` · ${formatDuration(fast.minutesToEdge)}`
  let label: string
  let value: string
  if (day === 'tomorrow') [label, value] = ['Fenêtre prévue', `${fast.windowStart} – ${fast.windowEnd}`]
  else if (fast.state === 'closed') [label, value] = ['Fenêtre fermée', '']
  else if (fast.state === 'open') [label, value] = ['Fenêtre ouverte', `ferme à ${fast.windowEnd}${left}`]
  else [label, value] = ['Jeûne en cours', `ouvre à ${fast.windowStart}${left}`]

  return <Line label={label} value={value} fraction={day === 'today' ? fast.progress : null} testId="home-health-fast" />
}
