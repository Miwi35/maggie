import Box from '@mui/material/Box'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import type { BalanceEnvelope, EnvelopeStatus } from './useMonthBalance'

/** A glyph and a word beside the colour: the status reads without it. */
const ENVELOPE_STATUS: Record<EnvelopeStatus, { word: string; glyph: string; color: 'success' | 'warning' | 'error' }> = {
  held: { word: 'Tenue', glyph: '✓', color: 'success' },
  close: { word: 'Proche', glyph: '▲', color: 'warning' },
  over: { word: 'Dépassée', glyph: '✕', color: 'error' },
}

/** One bar per root envelope: consumed against budgeted, held, close or over. */
export const EnvelopeBars = ({ envelopes }: { envelopes: BalanceEnvelope[] }) => {
  if (envelopes.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary" data-testid="envelope-bars-empty">
        Aucune enveloppe ce mois-ci.
      </Typography>
    )
  }

  return (
    <Stack spacing={1.5} data-testid="envelope-bars">
      {envelopes.map((envelope) => {
        const meta = ENVELOPE_STATUS[envelope.status]
        const fill = envelope.budgetCents > 0
          ? Math.min(100, (envelope.consumedCents / envelope.budgetCents) * 100)
          : envelope.consumedCents > 0 ? 100 : 0

        return (
          <Box key={envelope.envelopeId} data-testid="envelope-bar" data-status={envelope.status} data-name={envelope.name}>
            <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {envelope.name}
              </Typography>
              <Typography variant="caption" color={`${meta.color}.main`} sx={{ fontWeight: 600 }}>
                <span aria-hidden="true">{meta.glyph}</span> {meta.word}
              </Typography>
            </Stack>
            <LinearProgress
              variant="determinate"
              value={fill}
              color={meta.color}
              aria-label={`${envelope.name} : ${meta.word}`}
              sx={{ height: 10, borderRadius: 5, my: 0.5 }}
            />
            <Typography variant="caption" color="text.secondary">
              {formatCents(envelope.consumedCents)} sur {formatCents(envelope.budgetCents)}
              {envelope.percent !== null && ` · ${envelope.percent} %`}
            </Typography>
          </Box>
        )
      })}
    </Stack>
  )
}
