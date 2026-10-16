import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { RhythmCurve } from './RhythmCurve'
import { ScoreRings } from './ScoreRings'
import { useFinanceScores } from './useFinanceScores'
import type { FinanceScores } from './useFinanceScores'

interface FinanceScoresSectionProps {
  year: number
  month: number
  /**
   * Bumped by the host page when a transaction or an envelope changed. The
   * host already listens to those topics: a second EventSource here would take
   * one more of the browser's six connections per origin, and the page's own
   * requests would queue behind it.
   */
  refreshToken?: number
}

interface FinanceScoresCardProps {
  scores: FinanceScores | null
  loading?: boolean
  error?: boolean
  onRetry?: () => void
}

/**
 * The rings and the curve, drawn from scores the host already holds: the
 * finance home gets them in its dashboard payload (one read, one refresh);
 * the budget pages fetch their own through `FinanceScoresSection`.
 */
export const FinanceScoresCard = ({ scores, loading = false, error = false, onRetry }: FinanceScoresCardProps) => {
  const [period, setPeriod] = useState<'week' | 'month'>('month')

  return (
    <Card sx={{ mb: 2 }} data-testid="finance-scores">
      <CardContent>
        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
            Reste à dépenser
          </Typography>
          <ToggleButtonGroup
            size="small"
            exclusive
            value={period}
            onChange={(_, value) => value && setPeriod(value)}
            aria-label="Période de la courbe"
          >
            <ToggleButton value="week">Semaine</ToggleButton>
            <ToggleButton value="month">Mois</ToggleButton>
          </ToggleButtonGroup>
        </Stack>

        {error && (
          <Alert
            severity="error"
            sx={{ mb: 1 }}
            action={
              onRetry && (
                <Button color="inherit" size="small" onClick={onRetry}>
                  Réessayer
                </Button>
              )
            }
          >
            Les scores n'ont pas pu être chargés.
          </Alert>
        )}

        {loading && !scores && <LinearProgress />}

        {scores && (
          <Stack spacing={2}>
            <ScoreRings scores={scores} />
            <RhythmCurve rhythm={scores[period].rhythm} />
          </Stack>
        )}
      </CardContent>
    </Card>
  )
}

/** The rings and the curve of the period shown, fetched from `/api/finance/scores`. */
export const FinanceScoresSection = ({ year, month, refreshToken = 0 }: FinanceScoresSectionProps) => {
  const { scores, loading, error, refresh } = useFinanceScores(year, month)

  const seen = useRef(refreshToken)
  useEffect(() => {
    if (seen.current !== refreshToken) {
      seen.current = refreshToken
      refresh()
    }
  }, [refreshToken, refresh])

  return (
    <FinanceScoresCard
      scores={scores}
      loading={loading}
      error={error}
      onRetry={() => {
        refresh()
      }}
    />
  )
}
