import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { Link as RouterLink } from 'react-router-dom'
import { rhythmColor } from '../../design/tokens'
import { HomeCard } from '../home/HomeCard'
import { HomeCardError } from '../home/HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../home/homeCardStatus'
import type { HomeDay } from '../home/homeDay'
import { useHomeCardState } from '../home/useHomeCardState'
import { formatCents } from './accountTypes'
import { purchaseLabel } from './nextProject'
import { reviewLink } from './reviewFilter'
import { remainingShare, RHYTHM_META } from './useFinanceScores'
import type { Rhythm } from './useFinanceScores'

type HomeRing = Pick<Rhythm, 'status' | 'label' | 'budgetCents' | 'remainingCents' | 'expectedRemainingCents'>

interface HomeNextProject {
  projectId: string
  name: string
  amountCents: number
  gatheredCents: number
  purchaseMonth: string
}

interface FinanceHomeState {
  week: HomeRing
  month: HomeRing
  toQualifyCount: number
  nextProject: HomeNextProject | null
  accountCount: number
  envelopeCount: number
}

const SIZE = 96
const STROKE = 10
const RADIUS = (SIZE - STROKE) / 2
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/**
 * The Finance tile of the Accueil (MAG-565): the week and the month as two rings
 * with their rhythm status, the lines still to qualify, and the next project.
 * Same figures as the Finance Dashboard; it does not follow « Demain ». Drawn
 * as soon as there is an account or an envelope, not before (MAG-594).
 */
export const FinanceHomeCard = ({ day }: { day: HomeDay }) => {
  const { state, loading, error, retry } = useHomeCardState<FinanceHomeState>('finance', day)
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
  const meta = RHYTHM_META[ring.status]
  const colour = rhythmColor(meta.color, theme.palette.mode)
  const hasBudget = ring.budgetCents > 0
  const share = remainingShare(ring.remainingCents, ring.budgetCents)
  const word = ring.label || meta.word
  const centre = SIZE / 2

  return (
    <Box
      data-testid="home-finance-ring"
      data-period={period}
      data-status={ring.status}
      role="group"
      aria-label={`${title} : ${word}, ${hasBudget ? `${formatCents(ring.remainingCents)} restants` : 'aucune enveloppe'}`}
      sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: '1 1 0', minWidth: 0 }}
    >
      <Typography variant="caption" sx={{ fontWeight: 600 }}>
        {title}
      </Typography>
      <Box sx={{ position: 'relative', width: SIZE, height: SIZE }}>
        <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true">
          <circle cx={centre} cy={centre} r={RADIUS} fill="none" stroke={theme.palette.action.disabledBackground} strokeWidth={STROKE} />
          {share > 0 && (
            <circle
              cx={centre}
              cy={centre}
              r={RADIUS}
              fill="none"
              stroke={colour}
              strokeWidth={STROKE}
              strokeLinecap="round"
              strokeDasharray={`${share * CIRCUMFERENCE} ${CIRCUMFERENCE}`}
              transform={`rotate(-90 ${centre} ${centre})`}
            />
          )}
        </svg>
        <Stack alignItems="center" justifyContent="center" sx={{ position: 'absolute', inset: 0 }}>
          {hasBudget ? (
            <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.1 }} data-testid="home-finance-remaining">
              {formatCents(ring.remainingCents)}
            </Typography>
          ) : (
            <Typography variant="caption" color="text.secondary" align="center">
              Aucune enveloppe
            </Typography>
          )}
        </Stack>
      </Box>
      {hasBudget && (
        <Stack direction="row" spacing={0.5} alignItems="center" sx={{ color: colour }} data-testid="home-finance-status">
          <Box
            component="span"
            aria-hidden="true"
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 16,
              height: 16,
              borderRadius: '50%',
              fontSize: 10,
              fontWeight: 600,
              bgcolor: colour,
              color: theme.palette.background.default,
            }}
          >
            {meta.glyph}
          </Box>
          <Typography variant="caption" sx={{ fontWeight: 600, color: 'inherit' }}>
            {word}
          </Typography>
        </Stack>
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
          {purchaseLabel(project.purchaseMonth)}
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
