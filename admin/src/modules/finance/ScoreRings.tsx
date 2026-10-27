import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { formatCents } from './accountTypes'
import { reviewLink } from './reviewFilter'
import { reasonText, ringShares } from './useFinanceScores'
import type { FinanceScores, PeriodScore } from './useFinanceScores'

const SIZE = 148
const STROKE = 14
const RADIUS = (SIZE - STROKE) / 2 - 6
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

interface ScoreRingProps {
  period: 'week' | 'month'
  title: string
  score: PeriodScore
  /** `YYYY-MM` the « Classer » link opens the review on; the current month when absent. */
  reviewMonth?: string
}

export const ScoreRing = ({ period, title, score, reviewMonth }: ScoreRingProps) => {
  const theme = useTheme()
  const [open, setOpen] = useState(false)
  const { rhythm } = score
  const colour = theme.palette.primary.main
  const hasPlan = rhythm.plannedCents > 0
  const shares = ringShares(rhythm)

  // The tick sits where the recurring debits expected so far would end the arc.
  const angle = -Math.PI / 2 + (shares.expected ?? 0) * 2 * Math.PI
  const centre = SIZE / 2
  const tick = {
    x1: centre + (RADIUS - STROKE / 2 - 3) * Math.cos(angle),
    y1: centre + (RADIUS - STROKE / 2 - 3) * Math.sin(angle),
    x2: centre + (RADIUS + STROKE / 2 + 3) * Math.cos(angle),
    y2: centre + (RADIUS + STROKE / 2 + 3) * Math.sin(angle),
  }

  return (
    <Box
      data-testid="score-ring"
      data-period={period}
      sx={{ flex: '1 1 200px', textAlign: 'center' }}
    >
      <ButtonBase
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-label={`${title} : ${formatCents(rhythm.spentCents)} dépensés${hasPlan ? `, ${formatCents(rhythm.expectedSpentCents)} attendus` : ''}. Afficher les raisons`}
        sx={{ display: 'flex', flexDirection: 'column', width: '100%', borderRadius: 2, p: 1 }}
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
          {title}
        </Typography>

        <Box sx={{ position: 'relative', width: SIZE, height: SIZE, my: 0.5 }}>
          <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true">
            <circle
              cx={centre}
              cy={centre}
              r={RADIUS}
              fill="none"
              stroke={theme.palette.action.disabledBackground}
              strokeWidth={STROKE}
            />
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
                data-testid="rhythm-tick"
                {...tick}
                stroke={theme.palette.text.primary}
                strokeWidth={3}
                strokeLinecap="round"
              />
            )}
          </svg>

          <Stack
            alignItems="center"
            justifyContent="center"
            sx={{ position: 'absolute', inset: 0 }}
          >
            <Typography variant="h6" sx={{ lineHeight: 1.1 }} data-testid="ring-spent">
              {formatCents(rhythm.spentCents)}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              dépensés
            </Typography>
          </Stack>
        </Box>

        {hasPlan && (
          <Typography variant="caption" color="text.secondary" data-testid="ring-expected">
            Attendu {formatCents(rhythm.expectedSpentCents)}
          </Typography>
        )}
      </ButtonBase>

      {rhythm.unclassifiedCount > 0 && (
        <Typography variant="caption" color="text.secondary" component="p" sx={{ m: 0 }} data-testid="ring-unclassified">
          {formatCents(rhythm.unclassifiedCents)} non classés ·{' '}
          <Link
            component={RouterLink}
            to={reviewLink({ state: 'unrecognised', month: reviewMonth })}
            data-testid="ring-unclassified-link"
          >
            Classer
          </Link>
        </Typography>
      )}

      {open && (
        <Box component="ul" data-testid="score-reasons" sx={{ m: 0, mt: 1, pl: 2, textAlign: 'left' }}>
          {score.reasons.length === 0 && (
            <Typography component="li" variant="caption">
              Rien à signaler.
            </Typography>
          )}
          {score.reasons.map((reason, index) => (
            <Typography
              component="li"
              variant="caption"
              key={`${reason.code}-${reason.categoryName ?? ''}-${index}`}
            >
              {reasonText(reason)}
            </Typography>
          ))}
        </Box>
      )}
    </Box>
  )
}

interface ScoreRingsProps {
  scores: FinanceScores
}

/**
 * The week and the month as two rings: what was spent, every debit counted, and
 * a tick where the recurring debits expected so far end. Spending more than
 * expected is normal, so the ring gives no verdict. Touching a ring gives the
 * reasons of its daily score. Stand-alone on purpose — the home screen reuses it.
 */
export const ScoreRings = ({ scores }: ScoreRingsProps) => (
  <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2 }} justifyContent="space-around">
    <ScoreRing period="week" title="Semaine" score={scores.week} reviewMonth={scores.date.slice(0, 7)} />
    <ScoreRing period="month" title="Mois" score={scores.month} reviewMonth={scores.date.slice(0, 7)} />
  </Stack>
)
