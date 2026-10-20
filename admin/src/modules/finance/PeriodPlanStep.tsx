import { useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { formatCents } from './accountTypes'
import { PERIOD_WORDS, periodLabel } from './reviewActivity'
import { usePeriodPlan } from './usePeriodPlan'
import type { PlanCategory, PlanItem } from './usePeriodPlan'
import type { PlanningPeriodRef } from './useReviewProgress'

interface OneOff {
  name: string
  amount: string
}

const toCents = (text: string): number => {
  const value = Number(text.replace(',', '.').trim())

  return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0
}

const toText = (cents: number): string => String(cents / 100)

/** What one category is proposed at: a year gets an annual envelope, a shorter period a monthly one. */
const proposed = (category: PlanCategory, yearly: boolean, months: number): number =>
  category.envelopeCents !== null
    ? yearly
      ? category.envelopeCents
      : Math.round(category.envelopeCents / months)
    : yearly
      ? category.suggestedCents
      : category.suggestedMonthlyCents

/**
 * The review that closes a quarter, a semester or a year, step « Planification » (MAG-462): the
 * period ahead, one line per category with what the period behind cost and what is proposed.
 * « Appliquer le plan » writes the lines in one gesture — all of them, or none (the API refuses a
 * plan with a bad line before writing any).
 */
export const PeriodPlanStep = ({ period }: { period: PlanningPeriodRef }) => {
  const { plan, error, applying, apply, retry } = usePeriodPlan(period.key)
  const yearly = period.level === 'year'
  const [edits, setEdits] = useState<Record<string, string>>({})
  const [oneOffs, setOneOffs] = useState<OneOff[]>([])
  const [draft, setDraft] = useState<OneOff>({ name: '', amount: '' })
  const [failure, setFailure] = useState<{ message: string; line: string | null } | null>(null)
  const [applied, setApplied] = useState(false)

  const rows = useMemo(
    () => (plan?.categories ?? []).filter((category) => category.envelopeCents !== null || category.suggestedCents > 0),
    [plan],
  )

  // A line already set and left alone is not sent again: the plan would replace its seasons.
  const lines = useMemo(() => {
    const items: Array<{ label: string; item: PlanItem }> = []
    for (const category of rows) {
      const edited = edits[category.categoryId]
      if (edited === undefined && category.envelopeCents !== null) continue
      const amountCents = toCents(edited ?? toText(proposed(category, yearly, period.months)))
      if (amountCents <= 0) continue
      items.push({
        label: category.categoryName,
        item: { kind: yearly ? 'annual' : 'monthly', categoryId: category.categoryId, amountCents, currency: category.currency },
      })
    }
    for (const oneOff of oneOffs) {
      items.push({ label: oneOff.name, item: { kind: 'one_off', name: oneOff.name, amountCents: toCents(oneOff.amount), currency: 'EUR' } })
    }

    return items
  }, [rows, edits, oneOffs, yearly, period.months])

  const submit = async () => {
    setFailure(null)
    setApplied(false)
    const result = await apply(lines.map((line) => line.item))
    if (result.ok) {
      setApplied(true)
      setEdits({})
      setOneOffs([])
    } else {
      setFailure({ message: result.message, line: result.index === null ? null : (lines[result.index]?.label ?? null) })
    }
  }

  const addOneOff = () => {
    if (!draft.name.trim() || toCents(draft.amount) <= 0) return
    setOneOffs((current) => [...current, { name: draft.name.trim(), amount: draft.amount }])
    setDraft({ name: '', amount: '' })
  }

  if (error) {
    return (
      <Alert severity="error" action={<Button color="inherit" size="small" onClick={retry}>Réessayer</Button>}>
        Le plan n'a pas pu être chargé.
      </Alert>
    )
  }
  if (!plan) return <LinearProgress aria-label="Chargement" />

  const unit = yearly ? 'par an' : 'par mois'

  return (
    <Stack spacing={2} data-testid="review-period-plan" data-period={period.key}>
      <Typography variant="body2" color="text.secondary">
        Planification {PERIOD_WORDS[period.level].next} · {periodLabel(period)} — proposée d'après {periodLabel(plan.sourcePeriod)}.
      </Typography>

      {applied && (
        <Alert severity="success" data-testid="plan-applied">
          Plan appliqué : les enveloppes {PERIOD_WORDS[period.level].next} sont en place.
        </Alert>
      )}
      {failure && (
        <Alert severity="error" data-testid="plan-error">
          {failure.line ? `Ligne « ${failure.line} » refusée : ` : ''}
          {failure.message} Rien n'a été écrit.
        </Alert>
      )}

      {rows.length === 0 && (
        <Typography color="text.secondary" data-testid="plan-empty">
          Rien à proposer : la période précédente n'a pas de dépenses. Ajoutez une enveloppe ponctuelle si besoin.
        </Typography>
      )}

      <Box>
        {rows.map((category) => {
          const set = category.envelopeCents !== null
          const value = edits[category.categoryId] ?? toText(proposed(category, yearly, period.months))

          return (
            <Stack
              key={category.categoryId}
              direction={{ xs: 'column', md: 'row' }}
              spacing={2}
              alignItems={{ md: 'center' }}
              data-testid="plan-row"
              data-category={category.categoryName}
              data-set={set ? 'true' : 'false'}
              sx={{ py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}
            >
              <Box sx={{ flexGrow: 1, minWidth: 200 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {category.categoryName}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  Avant : {formatCents(category.lastYear.consumedCents, category.currency)}
                  {category.decidedCents > 0 && ` · déjà décidé : ${formatCents(category.decidedCents, category.currency)}`}
                  {set && ' · enveloppe posée'}
                </Typography>
              </Box>
              <TextField
                size="small"
                type="number"
                label={`Montant ${unit}`}
                value={value}
                onChange={(event) => setEdits((current) => ({ ...current, [category.categoryId]: event.target.value }))}
                slotProps={{
                  htmlInput: { min: 0, step: 5, 'aria-label': `Montant ${unit} ${category.categoryName}` },
                  input: { endAdornment: <Typography variant="caption">€</Typography> },
                }}
                sx={{ width: 180 }}
              />
            </Stack>
          )
        })}
      </Box>

      <Stack spacing={1} data-testid="plan-one-offs">
        <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
          Enveloppes ponctuelles
        </Typography>
        {oneOffs.map((oneOff, index) => (
          <Stack key={`${oneOff.name}-${index}`} direction="row" alignItems="center" spacing={1} data-testid="plan-one-off">
            <Typography variant="body2" sx={{ flexGrow: 1 }}>
              {oneOff.name} — {formatCents(toCents(oneOff.amount))}
            </Typography>
            <IconButton
              size="small"
              aria-label={`Retirer ${oneOff.name}`}
              onClick={() => setOneOffs((current) => current.filter((_, at) => at !== index))}
            >
              <DeleteOutlineIcon fontSize="small" />
            </IconButton>
          </Stack>
        ))}
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
          <TextField
            size="small"
            label="Nom"
            value={draft.name}
            onChange={(event) => setDraft({ ...draft, name: event.target.value })}
            slotProps={{ htmlInput: { maxLength: 120 } }}
            sx={{ flexGrow: 1 }}
          />
          <TextField
            size="small"
            type="number"
            label="Montant"
            value={draft.amount}
            onChange={(event) => setDraft({ ...draft, amount: event.target.value })}
            slotProps={{ htmlInput: { min: 0, step: 5 } }}
            sx={{ width: 140 }}
          />
          <Button onClick={addOneOff} disabled={!draft.name.trim() || toCents(draft.amount) <= 0}>
            Ajouter
          </Button>
        </Stack>
      </Stack>

      <Box>
        <Button variant="contained" onClick={() => void submit()} disabled={applying || lines.length === 0} data-testid="plan-apply">
          Appliquer le plan
        </Button>
        <Typography variant="caption" color="text.secondary" sx={{ ml: 2 }} data-testid="plan-count">
          {lines.length} {lines.length > 1 ? 'lignes' : 'ligne'} à écrire
        </Typography>
      </Box>
    </Stack>
  )
}
