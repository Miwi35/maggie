import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Slider from '@mui/material/Slider'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import { formatCents } from './accountTypes'
import { formatPeriod, formatSeason } from './budgetModes'
import { useEnvelopeAdjustments } from './useEnvelopeAdjustments'
import type { Adjustment, AdjustmentLine } from './useEnvelopeAdjustments'

/** One press of ± moves the amount by 5 €. */
export const ADJUST_STEP_CENTS = 500

const periodOf = (yearMonth: string) => {
  const [year, month] = yearMonth.split('-').map(Number)

  return formatPeriod('monthly', year, month)
}

const clamp = (cents: number, max: number) => Math.min(Math.max(cents, 0), max)

/** The slider's reach: twice the largest figure of the row, never under 100 €. */
const reachOf = (line: AdjustmentLine) =>
  Math.max(10000, Math.ceil((2 * Math.max(line.actualCents, line.targetAmountCents, line.suggestion?.proposedCents ?? 0)) / ADJUST_STEP_CENTS) * ADJUST_STEP_CENTS)

const asAdjustment = (line: AdjustmentLine): Adjustment => ({
  categoryId: line.categoryId,
  amountCents: line.suggestion?.proposedCents ?? line.targetAmountCents,
  seasonal: line.suggestion?.seasonal != null,
})

interface RowProps {
  line: AdjustmentLine
  value: number
  applied: boolean
  busy: boolean
  onChange: (cents: number) => void
  onApply: () => void
}

const AdjustmentRow = ({ line, value, applied, busy, onChange, onApply }: RowProps) => {
  const { suggestion } = line
  const reach = reachOf(line)
  const money = (cents: number) => formatCents(cents, line.currency)
  const name = line.categoryName

  return (
    <Box
      data-testid="adjustment-row"
      data-category={name}
      data-suggested={suggestion ? 'true' : 'false'}
      sx={{ py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}
    >
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }}>
        <Box sx={{ minWidth: 200, flexGrow: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {line.categoryPath}
          </Typography>
          <Typography variant="caption" color="text.secondary" data-testid="adjustment-actual">
            Réel : {money(line.actualCents)} sur {money(line.amountCents)} budgétés
          </Typography>
        </Box>

        <Stack direction="row" alignItems="center" spacing={1} sx={{ minWidth: 320 }}>
          <IconButton
            size="small"
            aria-label={`Diminuer ${name}`}
            onClick={() => onChange(clamp(value - ADJUST_STEP_CENTS, reach))}
            disabled={busy || value <= 0}
          >
            <RemoveIcon fontSize="small" />
          </IconButton>
          <Slider
            size="small"
            min={0}
            max={reach}
            step={ADJUST_STEP_CENTS}
            value={Math.min(value, reach)}
            onChange={(_, next) => onChange(next as number)}
            getAriaLabel={() => `Montant de ${name}`}
            getAriaValueText={money}
            disabled={busy}
            sx={{ flexGrow: 1, minWidth: 120 }}
          />
          <IconButton
            size="small"
            aria-label={`Augmenter ${name}`}
            onClick={() => onChange(clamp(value + ADJUST_STEP_CENTS, reach))}
            disabled={busy || value >= reach}
          >
            <AddIcon fontSize="small" />
          </IconButton>
          <Typography variant="body2" sx={{ minWidth: 80, textAlign: 'right' }} data-testid="adjustment-amount">
            {money(value)}
          </Typography>
        </Stack>
      </Stack>

      {suggestion && (
        <Stack
          direction="row"
          spacing={1}
          alignItems="center"
          flexWrap="wrap"
          sx={{ mt: 0.75 }}
          data-testid="adjustment-suggestion"
          data-direction={suggestion.direction}
        >
          <Typography variant="body2" color={suggestion.direction === 'raise' ? 'warning.main' : 'info.main'}>
            {suggestion.direction === 'raise' ? 'Relever' : 'Baisser'} à {money(suggestion.proposedCents)}
            {suggestion.seasonal && ` (${formatSeason(suggestion.seasonal)})`}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ flexGrow: 1 }}>
            {suggestion.reason}
          </Typography>
          {applied ? (
            <Typography variant="caption" color="success.main" data-testid="adjustment-applied">
              Appliquée
            </Typography>
          ) : (
            <Button size="small" variant="outlined" onClick={onApply} disabled={busy} aria-label={`Appliquer pour ${name}`}>
              Appliquer
            </Button>
          )}
        </Stack>
      )}
    </Box>
  )
}

interface Props {
  /** The month read, « YYYY-MM »: the amounts changed here are those of the month after. */
  month: string
}

/**
 * Sets next month's envelopes against what this month really cost: the actual
 * of each monthly envelope, a ± and a slider for its next amount, and a
 * suggestion — with its reason — only for the envelopes that do not fit.
 * Nothing is written without a gesture: « Appliquer », « Tout appliquer » or
 * « Enregistrer » for the amounts moved by hand. Mounted by the budget screen,
 * and meant to be the « Enveloppes » step of the monthly review as well.
 */
export const EnvelopeAdjustments = ({ month }: Props) => {
  const { data, loading, loadError, saving, apply } = useEnvelopeAdjustments(month)
  const [drafts, setDrafts] = useState<Record<string, number>>({})
  const [applied, setApplied] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)

  const lines = data?.envelopes ?? []
  const dirty = lines.filter((line) => drafts[line.categoryId] !== undefined && drafts[line.categoryId] !== line.targetAmountCents)
  const pending = lines.filter((line) => line.suggestion && !applied.has(line.categoryId) && !dirty.includes(line))

  const send = async (adjustments: Adjustment[]) => {
    const outcome = await apply(adjustments)
    if (!outcome.ok) {
      setError(outcome.error)

      return
    }
    const ids = adjustments.map((adjustment) => adjustment.categoryId)
    setError(null)
    setApplied((current) => new Set([...current, ...ids]))
    setDrafts((current) => Object.fromEntries(Object.entries(current).filter(([id]) => !ids.includes(id))))
  }

  const change = (line: AdjustmentLine, cents: number) => {
    setDrafts((current) => ({ ...current, [line.categoryId]: cents }))
    setApplied((current) => new Set([...current].filter((id) => id !== line.categoryId)))
  }

  return (
    <Box data-testid="envelope-adjustments">
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }} flexWrap="wrap">
        <Typography variant="subtitle1" component="h2" sx={{ fontWeight: 600, flexGrow: 1 }}>
          Ajuster les enveloppes de {data ? periodOf(data.targetMonth) : 'ce mois'}
        </Typography>
        <Button
          size="small"
          variant="outlined"
          onClick={() => send(pending.map(asAdjustment))}
          disabled={saving || pending.length === 0}
        >
          Tout appliquer ({pending.length})
        </Button>
        <Button
          size="small"
          variant="contained"
          onClick={() =>
            send(dirty.map((line) => ({ categoryId: line.categoryId, amountCents: drafts[line.categoryId], seasonal: false })))
          }
          disabled={saving || dirty.length === 0}
        >
          Enregistrer ({dirty.length})
        </Button>
      </Stack>
      <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1 }}>
        Le réel de {periodOf(month)}, enveloppe par enveloppe. Rien n'est modifié tant que vous ne l'avez pas demandé.
      </Typography>

      {loading && !data && <LinearProgress />}
      {loadError && !data && <Alert severity="error">Impossible de lire les enveloppes.</Alert>}
      {error && (
        <Alert severity="error" sx={{ mb: 1 }}>
          {error}
        </Alert>
      )}
      {data && lines.length === 0 && (
        <Typography variant="body2" color="text.secondary">
          Aucune enveloppe mensuelle à ajuster.
        </Typography>
      )}

      {lines.map((line) => (
        <AdjustmentRow
          key={line.categoryId}
          line={line}
          value={drafts[line.categoryId] ?? line.targetAmountCents}
          applied={applied.has(line.categoryId)}
          busy={saving}
          onChange={(cents) => change(line, cents)}
          onApply={() => send([asAdjustment(line)])}
        />
      ))}
    </Box>
  )
}
