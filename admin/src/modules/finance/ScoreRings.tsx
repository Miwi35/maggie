import { useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { formatCents } from './accountTypes'
import { reasonText, remainingShare, STATUS_META } from './useFinanceScores'
import type { FinanceScores, PeriodScore } from './useFinanceScores'

const SIZE = 148
const STROKE = 14
const RADIUS = (SIZE - STROKE) / 2 - 6
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

interface ScoreRingProps {
  period: 'week' | 'month'
  title: string
  score: PeriodScore
}

export const ScoreRing = ({ period, title, score }: ScoreRingProps) => {
  const theme = useTheme()
  const [open, setOpen] = useState(false)
  const { rhythm } = score
  const meta = STATUS_META[score.status]
  const hasBudget = rhythm.budgetCents > 0
  const overspent = rhythm.remainingCents < 0
  const share = remainingShare(rhythm.remainingCents, rhythm.budgetCents)
  const expectedShare = remainingShare(rhythm.expectedRemainingCents, rhythm.budgetCents)

  const colour = (path: string): string => {
    const [group, shade] = path.split('.')
    const palette = theme.palette as unknown as Record<string, Record<string, string>>

    return palette[group]?.[shade] ?? theme.palette.text.secondary
  }

  // The tick sits where the arc would end if the pace had been kept to the day.
  const angle = -Math.PI / 2 + expectedShare * 2 * Math.PI
  const centre = SIZE / 2
  const tick = {
    x1: centre + (RADIUS - STROKE / 2 - 3) * Math.cos(angle),
    y1: centre + (RADIUS - STROKE / 2 - 3) * Math.sin(angle),
    x2: centre + (RADIUS + STROKE / 2 + 3) * Math.cos(angle),
    y2: centre + (RADIUS + STROKE / 2 + 3) * Math.sin(angle),
  }

  const word = score.label || meta.word

  return (
    <Box
      data-testid="score-ring"
      data-period={period}
      data-status={score.status}
      sx={{ flex: '1 1 200px', textAlign: 'center' }}
    >
      <ButtonBase
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-label={`${title} : ${word}, ${hasBudget ? `${formatCents(rhythm.remainingCents)} restants` : 'aucune enveloppe'}. Afficher les raisons`}
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
            {(share > 0 || overspent) && (
              <circle
                cx={centre}
                cy={centre}
                r={RADIUS}
                fill="none"
                stroke={colour(overspent ? 'error.main' : meta.color)}
                strokeWidth={STROKE}
                strokeLinecap="round"
                strokeDasharray={`${overspent ? CIRCUMFERENCE : share * CIRCUMFERENCE} ${CIRCUMFERENCE}`}
                transform={`rotate(-90 ${centre} ${centre})`}
              />
            )}
            {hasBudget && (
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
            {hasBudget ? (
              <>
                <Typography variant="h6" sx={{ lineHeight: 1.1 }} data-testid="ring-remaining">
                  {formatCents(rhythm.remainingCents)}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  restants
                </Typography>
              </>
            ) : (
              <Typography variant="caption" color="text.secondary">
                Aucune enveloppe
              </Typography>
            )}
          </Stack>
        </Box>

        <Stack direction="row" spacing={0.5} alignItems="center" sx={{ color: meta.color }}>
          <span aria-hidden="true">{meta.glyph}</span>
          <Typography variant="body2" sx={{ fontWeight: 600, color: 'inherit' }}>
            {word}
          </Typography>
        </Stack>

        {hasBudget && (
          <Typography variant="caption" color="text.secondary" data-testid="ring-expected">
            Devrait rester {formatCents(rhythm.expectedRemainingCents)}
          </Typography>
        )}
      </ButtonBase>

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
 * The week and the month as two rings: what is left of the budget, and a tick
 * where the pace says it should be today. Touching a ring gives the reasons of
 * its status. Stand-alone on purpose — the home screen reuses it.
 */
export const ScoreRings = ({ scores }: ScoreRingsProps) => (
  <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2 }} justifyContent="space-around">
    <ScoreRing period="week" title="Semaine" score={scores.week} />
    <ScoreRing period="month" title="Mois" score={scores.month} />
  </Stack>
)
