import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { monthLabel } from './upcomingLabels'
import type { UpcomingEnvelope, UpcomingMonth } from './useUpcomingProjection'

interface BarProps {
  /** Shared scale of every bar of the card. */
  scale: number
  amountCents: number
  committedCents: number
  projectedCents: number
  warn: boolean
}

/**
 * The projected spending of an envelope as a bar, its committed part filled,
 * and a tick where the envelope's written amount ends: a bar past the tick is
 * an envelope committed beyond itself.
 */
const EnvelopeBar = ({ scale, amountCents, committedCents, projectedCents, warn }: BarProps) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const colour = warn ? theme.palette.warning.main : chartColor(2, mode)
  const pct = (cents: number) => `${Math.min((cents / scale) * 100, 100)}%`

  return (
    <Box aria-hidden sx={{ position: 'relative', height: 10, borderRadius: 0.5, bgcolor: 'action.hover', overflow: 'hidden' }}>
      <Box sx={{ position: 'absolute', inset: 0, width: pct(projectedCents), bgcolor: colour, opacity: 0.3 }} />
      <Box
        data-testid="upcoming-envelope-committed"
        sx={{ position: 'absolute', top: 0, bottom: 0, left: 0, width: pct(committedCents), bgcolor: colour }}
      />
      <Box sx={{ position: 'absolute', top: 0, bottom: 0, left: pct(amountCents), width: 2, bgcolor: 'text.primary' }} />
    </Box>
  )
}

const EnvelopeRow = ({ envelope, scale }: { envelope: UpcomingEnvelope; scale: number }) => (
  <Box
    data-testid={`upcoming-envelope-${envelope.categoryName}`}
    data-category={envelope.categoryName}
    data-overcommitted={envelope.isOvercommitted ? 'true' : 'false'}
    sx={{ py: 1, borderBottom: '1px solid', borderColor: 'divider' }}
  >
    <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
      <Typography variant="body2" noWrap sx={{ minWidth: 0 }} title={envelope.categoryPath}>
        {envelope.categoryName}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 600, flexShrink: 0 }} data-testid="upcoming-envelope-projected">
        {formatCents(envelope.projectedCents)}
      </Typography>
    </Stack>
    <EnvelopeBar
      scale={scale}
      amountCents={envelope.amountCents}
      committedCents={envelope.committedCents}
      projectedCents={envelope.projectedCents}
      warn={envelope.isOvercommitted}
    />
    <Typography variant="caption" color={envelope.isOvercommitted ? 'warning.main' : 'text.secondary'}>
      {formatCents(envelope.committedCents)} engagés sur {formatCents(envelope.amountCents)}
      {envelope.isOvercommitted && " · engagé au-delà de l'enveloppe"}
    </Typography>
  </Box>
)

/** The envelopes of the month to come as they stand: the amounts written, a suggestion nobody applied weighs nothing. */
export const UpcomingEnvelopes = ({ month }: { month: UpcomingMonth }) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const scale = Math.max(1, month.unbudgetedCents, ...month.envelopes.map((envelope) => Math.max(envelope.projectedCents, envelope.committedCents)))

  return (
    <Card data-testid="upcoming-envelopes" sx={{ flex: '1 1 420px', minWidth: 0 }}>
      <CardContent>
        <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 600 }}>
          Enveloppes · {monthLabel(month.month)} (ajustées)
        </Typography>
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 1 }}>
          Les montants sont ceux enregistrés pour ce mois : une suggestion non appliquée ne pèse rien.
        </Typography>

        {month.envelopes.length === 0 ? (
          <Typography variant="body2" color="text.secondary" data-testid="upcoming-envelopes-empty">
            Aucune enveloppe pour ce mois.
          </Typography>
        ) : (
          month.envelopes.map((envelope) => <EnvelopeRow key={envelope.categoryId} envelope={envelope} scale={scale} />)
        )}

        <Box data-testid="upcoming-unbudgeted" sx={{ py: 1 }}>
          <Stack direction="row" justifyContent="space-between" alignItems="baseline">
            <Typography variant="body2">Hors enveloppe</Typography>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {formatCents(month.unbudgetedCents)}
            </Typography>
          </Stack>
          <Box aria-hidden sx={{ height: 10, borderRadius: 0.5, bgcolor: 'action.hover', overflow: 'hidden' }}>
            <Box sx={{ width: `${Math.min((month.unbudgetedCents / scale) * 100, 100)}%`, height: '100%', bgcolor: chartColor(2, mode), opacity: 0.55 }} />
          </Box>
          <Typography variant="caption" color="text.secondary">
            Engagé hors de toute enveloppe
          </Typography>
        </Box>

        <Stack direction="row" justifyContent="space-between" sx={{ pt: 1 }} data-testid="upcoming-envelopes-total">
          <Typography variant="body2" color="text.secondary">
            Dépenses projetées du mois
          </Typography>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {formatCents(month.projectedExpenseCents)}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  )
}
