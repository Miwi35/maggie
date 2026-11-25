import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import { HEALTH_SUIVI_PATH } from '../../../components/layout/navigation'
import { formatInteger } from '../../health/healthProfileApi'
import { formatDuration } from '../../health/workoutFormat'
import { HomeCard } from '../HomeCard'
import { HomeCardError } from '../HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../homeCardStatus'
import { moduleColor } from '../moduleColor'
import type { HomeDay } from '../homeDay'
import { useHealthCardState } from '../useHealthCardState'
import { isHealthEmpty, windowSegment } from './healthCardState'
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
  <Box
    sx={(theme) => ({
      display: 'flex',
      flexDirection: 'column',
      gap: 1,
      '& > * + *': { pt: '8px', borderTop: `1px solid ${theme.palette.divider}` },
    })}
  >
    {state.workout && <Workout workout={state.workout} />}
    {state.steps && <Steps steps={state.steps} />}
    {state.balance && <Balance balance={state.balance} day={state.day} />}
    {state.fast && <Fast fast={state.fast} day={state.day} />}
  </Box>
)

type BarSpec = { fraction: number; soft: boolean } | { segment: { start: number; length: number } }

const Line = ({ label, value, bar, testId }: { label: string; value: string; bar: BarSpec | null; testId: string }) => (
  <Box data-testid={testId} sx={{ display: 'flex', flexDirection: 'column', gap: 0.75 }}>
    <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1.5 }}>
      <Typography variant="body2" sx={{ fontSize: 13 }}>
        {label}
      </Typography>
      {value && (
        <Typography variant="body2" color="text.secondary" sx={{ fontSize: 13, textAlign: 'right' }}>
          {value}
        </Typography>
      )}
    </Box>
    {bar && <Bar bar={bar} label={label} />}
  </Box>
)

const Bar = ({ bar, label }: { bar: BarSpec; label: string }) => {
  const clamp = (share: number) => Math.min(100, Math.max(0, share * 100))
  const [left, width] = 'segment' in bar ? [clamp(bar.segment.start), clamp(bar.segment.length)] : [0, clamp(bar.fraction)]
  const soft = 'segment' in bar || bar.soft

  return (
    <Box
      role="img"
      aria-label={label}
      data-testid="home-health-bar"
      sx={(theme) => ({ position: 'relative', height: 6, borderRadius: 999, overflow: 'hidden', bgcolor: theme.palette.veilleuse.track })}
    >
      <Box
        data-testid="home-health-bar-fill"
        sx={(theme) => {
          const coral = moduleColor(theme, 'health')

          return { position: 'absolute', top: 0, bottom: 0, left: `${left}%`, width: `${width}%`, backgroundColor: soft ? alpha(coral, 0.55) : coral }
        }}
      />
    </Box>
  )
}

const Workout = ({ workout }: { workout: HealthWorkout }) => (
  <Typography data-testid="home-health-workout" sx={{ fontSize: 15 }}>
    {`${workout.title} · ${workout.time} · ${formatDuration(workout.durationMinutes)}`}
  </Typography>
)

const Steps = ({ steps }: { steps: HealthSteps }) => (
  <Line
    label="Pas"
    value={`${formatInteger(steps.count)} · objectif ${formatInteger(steps.goal)}`}
    bar={{ fraction: steps.count / steps.goal, soft: false }}
    testId="home-health-steps"
  />
)

const Balance = ({ balance, day }: { balance: HealthBalance; day: HomeDay }) => {
  const need = balance.needKcal === null ? '' : ` · besoin ${formatInteger(balance.needKcal)} kcal`

  return (
    <Line
      label={day === 'today' ? 'Bilan du jour' : 'Calories projetées'}
      value={day === 'today' ? `${formatInteger(balance.kcal)} mangées${need}` : `${formatInteger(balance.kcal)} kcal${need}`}
      bar={balance.needKcal !== null && balance.needKcal > 0 ? { fraction: balance.kcal / balance.needKcal, soft: day === 'tomorrow' } : null}
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

  let bar: BarSpec | null = null
  if (day === 'tomorrow') bar = { segment: windowSegment(fast) }
  else if (fast.progress !== null) bar = { fraction: fast.progress, soft: true }

  return <Line label={label} value={value} bar={bar} testId="home-health-fast" />
}
