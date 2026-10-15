import { useMemo, useRef, useState } from 'react'
import type { KeyboardEvent, PointerEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { SPLIT_STEP_CENTS, centsForTravel, moveBoundary, sharesOf } from './envelopeSplit'
import type { SplitShares } from './envelopeSplit'
import { useEnvelopeSplit } from './useEnvelopeSplit'
import type { BudgetLine } from './useBudgetStatus'

const SEGMENT_COLORS = ['primary.main', 'secondary.main', 'info.main', 'success.main', 'warning.main']

interface Props {
  mother: BudgetLine
  /** The envelopes directly below the mother, for the same period. */
  daughters: BudgetLine[]
  onSaved: () => void
  /** Inside a form that has its own save button, say what this one saves. */
  saveLabel?: string
}

/**
 * Shares a mother envelope between her daughters: one segment each, the rest
 * "libre", and a handle on every boundary between two neighbours (the last one
 * between the last daughter and the free part). Dragging — or the arrow keys,
 * Shift for ten euros at a time — moves a boundary; nothing is written until
 * "Enregistrer".
 */
export const EnvelopeSplitSlider = ({ mother, daughters, onSaved, saveLabel = 'Enregistrer' }: Props) => {
  const initial = useMemo(
    () => sharesOf(daughters.map((daughter) => daughter.amountCents), mother.amountCents),
    [daughters, mother.amountCents],
  )
  const [shares, setShares] = useState<SplitShares>(initial)
  const [error, setError] = useState<string | null>(null)
  const { save, saving } = useEnvelopeSplit()
  const barRef = useRef<HTMLDivElement>(null)
  const drag = useRef<{ index: number; startX: number; from: SplitShares } | null>(null)

  const total = mother.amountCents
  const dirty = shares.amounts.some((amount, i) => amount !== initial.amounts[i])
  const money = (cents: number) => formatCents(cents, mother.currency)
  const nameOf = (i: number) => daughters[i].categoryName
  const percent = (cents: number) => (total > 0 ? (cents / total) * 100 : 0)

  const onPointerDown = (index: number) => (event: PointerEvent<HTMLElement>) => {
    event.currentTarget.setPointerCapture?.(event.pointerId)
    drag.current = { index, startX: event.clientX, from: shares }
  }

  const onPointerMove = (event: PointerEvent<HTMLElement>) => {
    const current = drag.current
    if (!current) {
      return
    }
    const width = barRef.current?.getBoundingClientRect().width ?? 0
    const delta = centsForTravel(event.clientX - current.startX, width, total)
    setShares(moveBoundary(current.from, current.index, delta))
    setError(null)
  }

  const onPointerUp = () => {
    drag.current = null
  }

  const onKeyDown = (index: number) => (event: KeyboardEvent<HTMLElement>) => {
    const direction = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 }[event.key]
    if (!direction) {
      return
    }
    event.preventDefault()
    const step = SPLIT_STEP_CENTS * (event.shiftKey ? 10 : 1)
    setShares((current) => moveBoundary(current, index, direction * step))
    setError(null)
  }

  const onSave = async () => {
    const outcome = await save(
      mother.id,
      daughters.map((daughter, i) => ({ envelopeId: daughter.id, amountCents: shares.amounts[i] })),
    )
    if (outcome.ok) {
      setError(null)
      onSaved()

      return
    }
    setShares(initial)
    setError(
      outcome.refused
        ? "La répartition dépasse le montant de l'enveloppe : rien n'a été modifié."
        : "Impossible d'enregistrer la répartition.",
    )
  }

  const offsets = shares.amounts.map((_, i) =>
    percent(shares.amounts.slice(0, i + 1).reduce((sum, amount) => sum + amount, 0)),
  )

  return (
    <Box sx={{ mt: 1.5, mb: 1 }} data-testid="envelope-split" data-envelope={mother.categoryName}>
      <Box ref={barRef} sx={{ position: 'relative', display: 'flex', height: 28, mx: '6px' }}>
        {daughters.map((daughter, i) => (
          <Box
            key={daughter.id}
            data-testid="split-segment"
            data-category={daughter.categoryName}
            sx={{
              flexBasis: `${percent(shares.amounts[i])}%`,
              flexShrink: 0,
              bgcolor: SEGMENT_COLORS[i % SEGMENT_COLORS.length],
              opacity: 0.85,
              borderRadius: i === 0 ? '4px 0 0 4px' : 0,
            }}
          />
        ))}
        <Box
          data-testid="split-segment"
          data-category="libre"
          sx={{
            flexGrow: 1,
            border: '1px dashed',
            borderColor: 'text.disabled',
            borderRadius: daughters.length === 0 ? '4px' : '0 4px 4px 0',
          }}
        />

        {daughters.map((_, i) => (
          <Box
            key={`handle-${i}`}
            role="slider"
            tabIndex={0}
            aria-label={`Frontière entre ${nameOf(i)} et ${i + 1 < daughters.length ? nameOf(i + 1) : 'libre'}`}
            aria-orientation="horizontal"
            aria-valuemin={0}
            aria-valuemax={total}
            aria-valuenow={offsets[i] * total / 100}
            aria-valuetext={`${nameOf(i)} ${money(shares.amounts[i])}`}
            onPointerDown={onPointerDown(i)}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onPointerCancel={onPointerUp}
            onKeyDown={onKeyDown(i)}
            sx={{
              position: 'absolute',
              top: -4,
              bottom: -4,
              left: `${offsets[i]}%`,
              width: 12,
              ml: '-6px',
              borderRadius: 1,
              bgcolor: 'background.paper',
              border: '2px solid',
              borderColor: 'text.primary',
              cursor: 'ew-resize',
              touchAction: 'none',
              '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 },
            }}
          />
        ))}
      </Box>

      <Stack direction="row" flexWrap="wrap" columnGap={2} sx={{ mt: 1 }}>
        {daughters.map((daughter, i) => (
          <Typography
            key={daughter.id}
            variant="caption"
            data-testid="split-amount"
            data-category={daughter.categoryName}
          >
            {daughter.categoryName} {money(shares.amounts[i])}
          </Typography>
        ))}
        <Typography
          variant="caption"
          color="text.secondary"
          data-testid="split-amount"
          data-category="libre"
        >
          Libre {money(shares.freeCents)}
        </Typography>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {error}
        </Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
        <Button size="small" variant="contained" onClick={onSave} disabled={!dirty || saving}>
          {saveLabel}
        </Button>
        <Button size="small" onClick={() => setShares(initial)} disabled={!dirty || saving}>
          Annuler
        </Button>
      </Stack>
    </Box>
  )
}
