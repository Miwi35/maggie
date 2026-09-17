import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import { formatCents } from './accountTypes'
import { SCORE_LABELS, SCORE_SEVERITY, reasonText, useDailyScore } from './useDailyScore'

interface DailyScoreBannerProps {
  year: number
  month: number
}

/**
 * The daily signal, with the reasons behind it — a score on its own reads as
 * a verdict, so the reasons always come with it.
 */
export const DailyScoreBanner = ({ year, month }: DailyScoreBannerProps) => {
  const { score } = useDailyScore(year, month)

  if (!score) {
    return null
  }

  return (
    <Alert severity={SCORE_SEVERITY[score.score] ?? 'info'} sx={{ mb: 2 }}>
      <AlertTitle>{SCORE_LABELS[score.score] ?? score.score}</AlertTitle>
      <Box component="ul" sx={{ m: 0, pl: 2 }}>
        {score.reasons.map((reason) => (
          <li key={`${reason.code}-${reason.categoryName ?? ''}`}>{reasonText(reason)}</li>
        ))}
      </Box>
      {score.budget.totalBudgetedCents > 0 && (
        <Box sx={{ mt: 1 }}>
          {formatCents(score.budget.totalConsumedCents)} consommés sur{' '}
          {formatCents(score.budget.totalBudgetedCents)}
        </Box>
      )}
    </Alert>
  )
}
