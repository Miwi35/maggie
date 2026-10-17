import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router-dom'
import { formatCents } from './accountTypes'
import { DashboardTile, tileInnerLinkSx } from './DashboardTile'
import { RECURRING_OPERATIONS_PATH, REFERENCE_SALARY_SOURCE_LABELS } from './referenceSalary'
import { TodoPill } from './TodoPill'
import type { BudgetTile as BudgetTileData, DashboardTodo, TileCushion, TileEnvelope } from './useFinanceDashboard'

const BAR_AREA_HEIGHT = 72

/** `2026-10-28` → « 28 octobre », read as a calendar day (no timezone shift). */
export function dayLabel(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number)

  return new Date(year, month - 1, day).toLocaleDateString('fr-FR', {
    day: 'numeric',
    month: 'long',
  })
}

const barColor = (envelope: TileEnvelope) =>
  envelope.isOverspent ? 'error.main' : envelope.isOvercommitted ? 'warning.main' : 'primary.main'

/**
 * The month's root envelopes as a histogram: a column per envelope, as tall as
 * its budget, filled up to what was consumed. Red when overspent, amber when
 * the planned operations will overspend it.
 */
const EnvelopeHistogram = ({ envelopes }: { envelopes: TileEnvelope[] }) => {
  if (envelopes.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Aucune enveloppe sur ce mois.
      </Typography>
    )
  }

  const tallest = Math.max(...envelopes.map((e) => Math.max(e.budgetCents, e.consumedCents)), 1)

  return (
    <Stack direction="row" spacing={1} alignItems="flex-end" data-testid="envelope-histogram">
      {envelopes.map((envelope) => {
        const budgetHeight = Math.max(4, (envelope.budgetCents / tallest) * BAR_AREA_HEIGHT)
        const fill = Math.min(100, envelope.budgetCents > 0 ? (envelope.consumedCents / envelope.budgetCents) * 100 : envelope.consumedCents > 0 ? 100 : 0)
        const state = envelope.isOverspent ? 'overspent' : envelope.isOvercommitted ? 'overcommitted' : 'ok'

        return (
          <Box
            key={envelope.envelopeId}
            data-testid="budget-bar"
            data-category={envelope.name}
            data-state={state}
            title={`${envelope.name} : ${formatCents(envelope.consumedCents)} / ${formatCents(envelope.budgetCents)}`}
            sx={{ flex: '1 1 0', minWidth: 0, textAlign: 'center' }}
          >
            <Box
              sx={{
                height: budgetHeight,
                mx: 'auto',
                maxWidth: 28,
                borderRadius: 0.5,
                bgcolor: 'action.hover',
                display: 'flex',
                alignItems: 'flex-end',
                overflow: 'hidden',
                mt: `${BAR_AREA_HEIGHT - budgetHeight}px`,
              }}
            >
              <Box sx={{ width: '100%', height: `${fill}%`, bgcolor: barColor(envelope) }} />
            </Box>
            <Typography variant="caption" noWrap component="div" sx={{ mt: 0.5 }}>
              {envelope.name}
            </Typography>
            <Typography
              variant="caption"
              noWrap
              component="div"
              color={envelope.isOverspent ? 'error.main' : 'text.secondary'}
            >
              {formatCents(envelope.consumedCents)} / {formatCents(envelope.budgetCents)}
            </Typography>
          </Box>
        )
      })}
    </Stack>
  )
}

const CushionLine = ({ cushion }: { cushion: TileCushion }) => {
  let text: string
  if (!cushion.isConfigured) {
    text = 'Matelas non configuré'
  } else if (cushion.state === 'complete') {
    text = 'Matelas complet'
  } else {
    text = `Matelas à ${cushion.coveragePercent} % (${formatCents(cushion.currentCents)} sur ${formatCents(cushion.targetCents)})`
  }

  return (
    <Typography variant="body2" color="text.secondary" data-testid="cushion-status" data-state={cushion.state}>
      {text}
    </Typography>
  )
}

/** « Budget » → the budgets: envelopes, matelas, salary to come and the balance before it. */
export const BudgetTile = ({ tile, todo }: { tile: BudgetTileData; todo: DashboardTodo }) => {
  const { expectedSalary, balanceBeforeSalary } = tile

  return (
    <DashboardTile title="Budget" to="/envelopes" testId="tile-budget">
      {todo.advanceToRepayCents > 0 && (
        <Box sx={{ mb: 1 }}>
          <TodoPill
            label={`${formatCents(todo.advanceToRepayCents)} à remettre`}
            to="/finance/savings-advances"
            testId="pill-advance"
          />
        </Box>
      )}
      <EnvelopeHistogram envelopes={tile.envelopes} />

      <Box sx={{ mt: 1.5 }}>
        <CushionLine cushion={tile.cushion} />
      </Box>

      <Box sx={{ mt: 1.5 }} data-testid="expected-salary">
        <Typography variant="caption" color="text.secondary" component="div">
          Salaire attendu
        </Typography>
        {expectedSalary ? (
          <>
            <Typography variant="h6" component="div">
              {formatCents(expectedSalary.monthlyCents)}
              {expectedSalary.expectedOn && (
                <Typography component="span" variant="body2" color="text.secondary">
                  {' '}
                  · le {dayLabel(expectedSalary.expectedOn)}
                </Typography>
              )}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {REFERENCE_SALARY_SOURCE_LABELS[expectedSalary.source]}
            </Typography>
          </>
        ) : (
          <>
            <Typography variant="body1" color="text.secondary">
              salaire non déclaré
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Le mois est jugé sur ce repère.{' '}
              <Link component={RouterLink} to={RECURRING_OPERATIONS_PATH} sx={tileInnerLinkSx}>
                Ajouter l'opération récurrente « Salaire »
              </Link>
            </Typography>
          </>
        )}
      </Box>

      <Box sx={{ mt: 1.5 }} data-testid="balance-before-salary">
        <Typography variant="caption" color="text.secondary" component="div">
          Solde attendu avant le salaire
        </Typography>
        {balanceBeforeSalary ? (
          <Typography variant="h6" component="div" color={balanceBeforeSalary.balanceCents < 0 ? 'error.main' : undefined}>
            {formatCents(balanceBeforeSalary.balanceCents)}
            <Typography component="span" variant="body2" color="text.secondary">
              {' '}
              · le {dayLabel(balanceBeforeSalary.onDate)}
            </Typography>
          </Typography>
        ) : (
          <Typography variant="body1" color="text.secondary">
            solde attendu inconnu
          </Typography>
        )}
      </Box>
    </DashboardTile>
  )
}
