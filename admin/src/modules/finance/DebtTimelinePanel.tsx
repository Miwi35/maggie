import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import { formatMonth, formatRemaining } from './loans'
import { useDebtTimeline } from './useDebtTimeline'

const LOAN_TOPICS = ['/api/loans/{id}']

/** The relief schedule and what it leaves to save, above the loan list. */
export const DebtTimelinePanel = () => {
  const { timeline, loading, refresh } = useDebtTimeline()

  useMercure(LOAN_TOPICS, () => { refresh() })

  if (loading && !timeline) {
    return <LinearProgress sx={{ mb: 2 }} />
  }

  if (!timeline || timeline.loans.length === 0) {
    return null
  }

  const { savingCapacity: capacity } = timeline

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>
          Libération des charges
        </Typography>

        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {formatCents(timeline.totalPrincipalRemainingCents)} restant dû —{' '}
          {formatCents(timeline.totalMonthlyPaymentCents)} par mois, dont{' '}
          {formatCents(timeline.totalInterestOverHorizonCents)} d'intérêts sur{' '}
          {timeline.horizonMonths} mois
        </Typography>

        {timeline.reliefByMonth.map((relief) => (
          <Stack
            key={relief.month}
            // The handle the e2e journey uses (MAG-102): one line per month a
            // loan frees up, and the assertion is about which loan frees what.
            data-testid="debt-relief"
            data-month={relief.month}
            direction="row"
            justifyContent="space-between"
            sx={{ py: 0.5, borderBottom: '1px solid', borderColor: 'divider' }}
          >
            <Typography variant="body2">
              {formatMonth(relief.month)} — {relief.loans.join(', ')}
            </Typography>
            <Typography variant="body2" color="success.main">
              +{formatCents(relief.freedCents)}/mois (cumul{' '}
              {formatCents(relief.cumulativeFreedCents)})
            </Typography>
          </Stack>
        ))}

        {timeline.loans.some((loan) => loan.endsBeyondHorizon) && (
          <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
            Certains prêts courent au-delà de {timeline.horizonMonths} mois :{' '}
            {timeline.loans
              .filter((loan) => loan.endsBeyondHorizon)
              .map((loan) => loan.name)
              .join(', ')}
          </Typography>
        )}

        <Box sx={{ mt: 2 }}>
          {capacity.isIncomeKnown ? (
            <Typography variant="body2">
              Capacité d'épargne nette : <strong>{formatCents(capacity.netCapacityCents)}</strong>{' '}
              par mois — {formatCents(capacity.monthlyNetIncomeCents)} de revenu moins{' '}
              {formatCents(capacity.loanPaymentsCents)} de mensualités et{' '}
              {formatCents(capacity.estimatedLifestyleCents)} de train de vie mesuré sur les trois
              derniers mois.
            </Typography>
          ) : (
            <Alert severity="info" variant="outlined">
              Renseignez votre revenu net mensuel dans le matelas de sécurité pour connaître votre
              capacité d'épargne.
            </Alert>
          )}
        </Box>

        <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
          Prochaine libération :{' '}
          {timeline.loans[0]?.freedOn
            ? `${timeline.loans[0].name} dans ${formatRemaining(timeline.loans[0].monthsRemaining)}`
            : 'aucune sur cet horizon'}
        </Typography>
      </CardContent>
    </Card>
  )
}
