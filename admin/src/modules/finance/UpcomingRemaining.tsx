import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { monthLabel } from './upcomingLabels'
import type { UpcomingMonth } from './useUpcomingProjection'

const Legend = ({ colour, opacity = 1, label, cents }: { colour: string; opacity?: number; label: string; cents: number }) => (
  <Stack direction="row" spacing={0.75} alignItems="center">
    <Box sx={{ width: 10, height: 10, bgcolor: colour, opacity }} />
    <Typography variant="caption" color="text.secondary" sx={{ flexGrow: 1 }}>
      {label}
    </Typography>
    <Typography variant="caption">{formatCents(cents)}</Typography>
  </Stack>
)

/**
 * What is left toward the projects: the income expected in the month less the
 * spending it is projected to carry. One bar splits the income into the
 * envelopes, what is outside them and what remains; a month that spends more
 * than it earns overruns the income mark instead.
 */
export const UpcomingRemaining = ({ month }: { month: UpcomingMonth }) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const spending = chartColor(2, mode)
  const remainder = chartColor(1, mode)
  const short = month.remainingCents < 0
  const scale = Math.max(1, month.incomeCents, month.projectedExpenseCents)
  const pct = (cents: number) => `${(Math.max(cents, 0) / scale) * 100}%`

  return (
    <Card data-testid="upcoming-remaining" data-short={short ? 'true' : 'false'} sx={{ flex: '1 1 320px', minWidth: 0 }}>
      <CardContent>
        <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 600 }}>
          Reste vers les projets
        </Typography>
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 1 }}>
          Revenus attendus moins dépenses projetées, en {monthLabel(month.month)}
        </Typography>

        {short ? (
          <Box data-testid="upcoming-remaining-amount">
            <Typography variant="h5" component="div" color="warning.main" sx={{ fontWeight: 600 }}>
              Mois déficitaire de {formatCents(-month.remainingCents)}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Les dépenses projetées dépassent les revenus attendus : rien ne reste pour les projets.
            </Typography>
          </Box>
        ) : (
          <Typography variant="h4" component="div" sx={{ fontWeight: 600 }} data-testid="upcoming-remaining-amount">
            {formatCents(month.remainingCents)}
          </Typography>
        )}

        <Box
          role="img"
          aria-label={`${formatCents(month.incomeCents)} de revenus pour ${formatCents(month.projectedExpenseCents)} de dépenses projetées`}
          sx={{ position: 'relative', height: 14, borderRadius: 0.5, bgcolor: 'action.hover', overflow: 'hidden', my: 1.5 }}
        >
          <Box sx={{ display: 'flex', height: '100%', width: pct(Math.max(month.projectedExpenseCents, month.incomeCents)) }}>
            <Box sx={{ width: pct(month.envelopesCents), bgcolor: spending }} />
            <Box sx={{ width: pct(month.unbudgetedCents), bgcolor: spending, opacity: 0.55 }} />
            {!short && <Box sx={{ width: pct(month.remainingCents), bgcolor: remainder }} />}
          </Box>
          {short && <Box sx={{ position: 'absolute', top: 0, bottom: 0, left: pct(month.incomeCents), width: 2, bgcolor: 'text.primary' }} />}
        </Box>

        <Stack spacing={0.25}>
          <Typography variant="caption" color="text.secondary" component="div" data-testid="upcoming-remaining-income">
            Revenus attendus : {formatCents(month.incomeCents)}
          </Typography>
          <Legend colour={spending} label="Enveloppes" cents={month.envelopesCents} />
          <Legend colour={spending} opacity={0.55} label="Hors enveloppe" cents={month.unbudgetedCents} />
          {!short && <Legend colour={remainder} label="Reste" cents={month.remainingCents} />}
        </Stack>
      </CardContent>
    </Card>
  )
}
