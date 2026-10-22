import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { DashboardTile } from './DashboardTile'
import { IndependenceSummary } from './IndependenceSummary'
import { ProjectGantt } from './ProjectGantt'
import type { PlanningTile as PlanningTileData, SavingCapacity } from './useFinanceDashboard'
import { useProjectPlanning } from './useProjectPlanning'

const CapacitySummary = ({ capacity }: { capacity: SavingCapacity }) => (
  <Box data-testid="saving-capacity">
    <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
      Capacité d'épargne nette
    </Typography>
    {capacity.isIncomeKnown ? (
      <>
        <Typography variant="h5" sx={{ my: 0.5 }}>
          {formatCents(capacity.netCapacityCents)}
          <Typography component="span" variant="body2" color="text.secondary">
            {' '}
            / mois
          </Typography>
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {formatCents(capacity.monthlyNetIncomeCents)} de revenu −{' '}
          {formatCents(capacity.loanPaymentsCents)} de mensualités −{' '}
          {formatCents(capacity.estimatedLifestyleCents)} de train de vie
        </Typography>
      </>
    ) : (
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        Renseignez votre revenu net dans le matelas de sécurité pour l'obtenir.
      </Typography>
    )}
  </Box>
)

/**
 * « Planification » → the treasury outlook (`/finance/treasury`), the admin's
 * forward-looking page: how long the rentes take to cover the train de vie,
 * and what is left to save each month, with the first three projects as a
 * mini Gantt (MAG-529).
 */
export const PlanningTile = ({ tile }: { tile: PlanningTileData }) => {
  const { planning } = useProjectPlanning()

  return (
    <DashboardTile title="Planification" to="/finance/treasury" testId="tile-planning">
      <CapacitySummary capacity={tile.savingCapacity} />
      {planning && (
        <Box sx={{ mt: 2 }} data-testid="planning-gantt">
          <ProjectGantt planning={planning} variant="compact" />
        </Box>
      )}
      <Box sx={{ mt: 2 }}>
        <IndependenceSummary counter={tile.independence} />
      </Box>
    </DashboardTile>
  )
}
