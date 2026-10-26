import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { Link as RouterLink } from 'react-router-dom'
import { HomeCard } from '../home/HomeCard'
import { HomeCardError } from '../home/HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../home/homeCardStatus'
import { formatCents } from './accountTypes'
import { purchaseLabel } from './nextProject'
import { reviewLink } from './reviewFilter'
import { useFinanceCardState } from './useFinanceCardState'
import { ringShares } from './useFinanceScores'
import type { FinanceHomeState, HomeNextProject, HomeRing } from './financeCardState'

const SIZE = 96
const STROKE = 10
const RADIUS = (SIZE - STROKE) / 2
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/**
 * The Finance tile of the Accueil (MAG-565): the week and the month as two rings
 * with what was spent, the lines still to qualify, and the next project.
 * Read from the Finance dashboard resource (MAG-592), so the same figures as the
 * Finance Dashboard; it does not follow « Demain ». Drawn as soon as there is an
 * account or an envelope, not before (MAG-594).
 */
export const FinanceHomeCard = () => {
  const { state, loading, error, retry } = useFinanceCardState()
  const status = homeCardStatus({ state, loading, error }, isEmpty)
  useReportHomeCard('finance', status)

  if (status === 'empty') return null

  return (
    <HomeCard module="finance" title="Finance" to="/finance/dashboard">
      {state && !isEmpty(state) ? <Body state={state} /> : !error && loading ? <Loading /> : null}
      {error && <HomeCardError onRetry={() => void retry()} />}
    </HomeCard>
  )
}

const isEmpty = (state: FinanceHomeState) => state.accountCount === 0 && state.envelopeCount === 0

const Loading = () => (
  <Box aria-busy="true" data-testid="home-finance-loading">
    <Skeleton width="60%" />
    <Skeleton width="80%" />
    <Skeleton width="50%" />
  </Box>
)

const Body = ({ state }: { state: FinanceHomeState }) => (
  <Stack spacing={1.5}>
    <Stack direction="row" spacing={2} justifyContent="space-around">
      <Ring period="week" title="Semaine" ring={state.week} />
      <Ring period="month" title="Mois" ring={state.month} />
    </Stack>
    {state.toQualifyCount > 0 && (
      <Box>
        <Chip
          component={RouterLink}
          to={reviewLink({ state: 'unrecognised', period: 'all' })}
          clickable
          size="small"
          color="warning"
          variant="outlined"
          label={`${state.toQualifyCount} à qualifier`}
          data-testid="home-finance-to-qualify"
        />
      </Box>
    )}
    {state.nextProject && <NextProject project={state.nextProject} />}
  </Stack>
)

const Ring = ({ period, title, ring }: { period: 'week' | 'month'; title: string; ring: HomeRing }) => {
  const theme = useTheme()
  const colour = theme.palette.primary.main
  const shares = ringShares(ring)
  const hasPlan = ring.plannedCents > 0
  const centre = SIZE / 2
  const angle = -Math.PI / 2 + (shares.expected ?? 0) * 2 * Math.PI
  const inner = RADIUS - STROKE / 2 - 2
  const outer = RADIUS + STROKE / 2 + 2

  return (
    <Box
      data-testid="home-finance-ring"
      data-period={period}
      role="group"
      aria-label={`${title} : ${formatCents(ring.spentCents)} dépensés${hasPlan ? `, ${formatCents(ring.expectedSpentCents)} attendus` : ''}`}
      sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: '1 1 0', minWidth: 0 }}
    >
      <Typography variant="caption" sx={{ fontWeight: 600 }}>
        {title}
      </Typography>
      <Box sx={{ position: 'relative', width: SIZE, height: SIZE }}>
        <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true">
          <circle cx={centre} cy={centre} r={RADIUS} fill="none" stroke={theme.palette.action.disabledBackground} strokeWidth={STROKE} />
          {shares.spent > 0 && (
            <circle
              cx={centre}
              cy={centre}
              r={RADIUS}
              fill="none"
              stroke={colour}
              strokeWidth={STROKE}
              strokeLinecap="round"
              strokeDasharray={`${shares.spent * CIRCUMFERENCE} ${CIRCUMFERENCE}`}
              transform={`rotate(-90 ${centre} ${centre})`}
            />
          )}
          {shares.expected !== null && (
            <line
              data-testid="home-finance-tick"
              x1={centre + inner * Math.cos(angle)}
              y1={centre + inner * Math.sin(angle)}
              x2={centre + outer * Math.cos(angle)}
              y2={centre + outer * Math.sin(angle)}
              stroke={theme.palette.text.primary}
              strokeWidth={2.5}
              strokeLinecap="round"
            />
          )}
        </svg>
        <Stack alignItems="center" justifyContent="center" sx={{ position: 'absolute', inset: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.1 }} data-testid="home-finance-spent">
            {formatCents(ring.spentCents)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            dépensés
          </Typography>
        </Stack>
      </Box>
      {hasPlan && (
        <Typography variant="caption" color="text.secondary" data-testid="home-finance-expected">
          Attendu {formatCents(ring.expectedSpentCents)}
        </Typography>
      )}
    </Box>
  )
}

const NextProject = ({ project }: { project: HomeNextProject }) => {
  const percent = Math.min(100, Math.round((project.gatheredCents / Math.max(1, project.amountCents)) * 100))

  return (
    <Box data-testid="home-finance-next-project">
      <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
        <Typography variant="body2" sx={{ fontWeight: 600 }} noWrap>
          {project.name}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
          {project.purchaseMonth ? purchaseLabel(project.purchaseMonth) : `au-delà de ${project.horizonMonths} mois`}
        </Typography>
      </Stack>
      <Typography variant="caption" color="text.secondary">
        {formatCents(project.gatheredCents)} sur {formatCents(project.amountCents)}
      </Typography>
      <LinearProgress
        variant="determinate"
        value={percent}
        aria-label={`${project.name} : ${percent} % atteint`}
        sx={{ height: 6, borderRadius: 3, mt: 0.5 }}
      />
    </Box>
  )
}
