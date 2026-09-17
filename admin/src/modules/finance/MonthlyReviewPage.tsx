import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Title, useNotify } from 'react-admin'
import { formatCents } from './accountTypes'
import { MONTH_CHOICES } from './budgetModes'
import { useMonthlyReview } from './useMonthlyReview'
import type { MonthlyReview, PendingSpend, Verdict } from './useMonthlyReview'

const lastMonth = () => {
  const now = new Date()

  return new Date(now.getFullYear(), now.getMonth() - 1, 1)
}

const ComparisonLine = ({ review }: { review: MonthlyReview }) => {
  const { comparison } = review
  const versus = (label: string, cents: number) => {
    const difference = comparison.thisMonthCents - cents
    const sign = difference > 0 ? '+' : ''

    return `${label} : ${formatCents(cents)} (${sign}${formatCents(difference)})`
  }

  return (
    <Typography variant="body2" color="text.secondary">
      {formatCents(comparison.thisMonthCents)} dépensés ce mois-ci —{' '}
      {versus('mois précédent', comparison.previousMonthCents)} ·{' '}
      {versus('moyenne 3 mois', comparison.recentAverageCents)} ·{' '}
      {versus("l'an dernier", comparison.sameMonthLastYearCents)}
    </Typography>
  )
}

const PendingRow = ({
  spend,
  onRate,
}: {
  spend: PendingSpend
  onRate: (id: string, verdict: Verdict) => void
}) => (
  <Stack
    direction="row"
    spacing={2}
    alignItems="center"
    sx={{ py: 1, borderBottom: '1px solid', borderColor: 'divider', flexWrap: 'wrap' }}
  >
    <Box sx={{ flexGrow: 1, minWidth: 200 }}>
      <Typography variant="body2">{spend.label}</Typography>
      <Typography variant="caption" color="text.secondary">
        {spend.bookedAt} · {spend.categoryName ?? 'Non catégorisée'}
      </Typography>
    </Box>
    <Typography variant="body2" sx={{ minWidth: 90, textAlign: 'right' }}>
      {formatCents(Math.abs(spend.amountCents), spend.currency)}
    </Typography>
    <Button size="small" onClick={() => onRate(spend.id, 'keep')}>
      À conserver
    </Button>
    <Button size="small" color="warning" onClick={() => onRate(spend.id, 'avoidable')}>
      J'aurais pu m'en passer
    </Button>
  </Stack>
)

export const MonthlyReviewPage = () => {
  const previous = lastMonth()
  const [year, setYear] = useState(previous.getFullYear())
  const [month, setMonth] = useState(previous.getMonth() + 1)
  const { review, loading, rate } = useMonthlyReview(year, month)
  const notify = useNotify()

  const onRate = async (id: string, verdict: Verdict) => {
    const ok = await rate(id, verdict)
    if (!ok) {
      notify("Impossible d'enregistrer ce choix", { type: 'error' })
    }
  }

  return (
    <>
      <Title title="Revue mensuelle" />

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2, flexWrap: 'wrap' }}>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>
              Revue du mois
            </Typography>
            <TextField
              select
              size="small"
              label="Mois"
              value={month}
              onChange={(e) => setMonth(Number(e.target.value))}
              sx={{ minWidth: 140 }}
            >
              {MONTH_CHOICES.map((c) => (
                <MenuItem key={c.id} value={c.id}>
                  {c.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              type="number"
              size="small"
              label="Année"
              value={year}
              onChange={(e) => setYear(Number(e.target.value))}
              sx={{ width: 110 }}
            />
          </Stack>

          {loading && !review && <LinearProgress />}

          {review && (
            <>
              <ComparisonLine review={review} />

              <Typography variant="body2" sx={{ mt: 1 }}>
                {review.optimisationScore === null
                  ? "Aucune dépense qualifiée pour l'instant — le score apparaîtra dès le premier choix."
                  : `Score d'optimisation : ${review.optimisationScore} % de ce que vous avez jugé était à conserver.`}
              </Typography>

              {review.avoidableCents > 0 && (
                <Typography variant="body2" color="text.secondary">
                  {formatCents(review.avoidableCents)} jugés évitables — autant qu'un mois
                  identique pourrait rendre.
                </Typography>
              )}
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
            Dépenses à qualifier
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Seules les dépenses non-obligatoires sont proposées, les plus grosses d'abord.
          </Typography>

          {review?.pending.length === 0 && (
            <Alert severity="success" variant="outlined">
              {review.ratedCount > 0
                ? 'Tout est qualifié pour ce mois.'
                : "Aucune dépense non-obligatoire sur ce mois."}
            </Alert>
          )}

          {review?.pending.map((spend) => (
            <PendingRow key={spend.id} spend={spend} onRate={onRate} />
          ))}
        </CardContent>
      </Card>
    </>
  )
}
