import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { AccountsTile } from './AccountsTile'
import { BudgetTile } from './BudgetTile'
import { dayOfMonthLabel, ofMonth, syncedLabel } from './dashboardHeader'
import { NextProjectCard } from './NextProjectCard'
import { PlanningTile } from './PlanningTile'
import { ReviewActionCard } from './ReviewActionCard'
import { RhythmCurve } from './RhythmCurve'
import { ScoreRing } from './ScoreRings'
import { useLastBankSync } from './useLastBankSync'
import type { FinanceDashboard } from './useFinanceDashboard'

const RingCard = ({ period, title, dashboard }: { period: 'week' | 'month'; title: string; dashboard: FinanceDashboard }) => (
  <Card sx={{ flex: '1 1 220px' }} data-testid={`ring-card-${period}`}>
    <CardContent>
      <ScoreRing period={period} title={title} score={dashboard.scores[period]} reviewMonth={dashboard.scores.date.slice(0, 7)} />
    </CardContent>
  </Card>
)

/**
 * Dashboard › État courant, as the mock-up draws it: the day of the month and
 * the last bank synchronisation, the review when it is due (an action card),
 * the week and month rings beside « Prochain projet », the month's spending
 * against its rhythm, then Comptes, Budget and Planification, each opening its
 * menu. One read of the dashboard feeds all of it.
 */
export const CurrentStateTab = ({ dashboard, now = new Date() }: { dashboard: FinanceDashboard; now?: Date }) => {
  const syncedAt = useLastBankSync()

  return (
    <>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }} data-testid="dashboard-header">
        {dayOfMonthLabel(now)}
        {syncedAt && ` · ${syncedLabel(syncedAt, now)}`}
      </Typography>

      {dashboard.todo.reviewReady && <ReviewActionCard now={now} />}

      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch', mb: 2 }}>
        <RingCard period="week" title="Semaine" dashboard={dashboard} />
        <RingCard period="month" title="Mois" dashboard={dashboard} />
        {dashboard.nextProject && (
          <NextProjectCard project={dashboard.nextProject} sx={{ flex: '2 1 360px', mb: 0 }} />
        )}
      </Stack>

      <Card sx={{ mb: 2 }} data-testid="month-spending-card">
        <CardContent>
          <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
            Dépenses {ofMonth(now)}
          </Typography>
          <RhythmCurve rhythm={dashboard.scores.month.rhythm} />
        </CardContent>
      </Card>

      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
        <AccountsTile tile={dashboard.tiles.accounts} todo={dashboard.todo} />
        <BudgetTile tile={dashboard.tiles.budget} todo={dashboard.todo} />
        <PlanningTile tile={dashboard.tiles.planning} />
      </Stack>
    </>
  )
}
