import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import { HEALTH_NUTRITION_PATH, HEALTH_SUIVI_PATH } from '../../components/layout/navigation'
import { MAX_SCORE, MIN_SCORE } from './healthDaysApi'
import type { HealthDay, HealthDayPatch } from './healthDaysApi'
import { formatBalance, formatClock, formatMinutes, formatSteps, formatWeight, journalDayLabel } from './healthJournal'
import { cardSx, shiftDay } from './nutritionBalance'
import type { Loaded } from './nutritionApi'

/** Santé › Sport is the workouts' resource list. */
const HEALTH_SPORT_PATH = '/workouts'

const NOTE_MAX_LENGTH = 2000

const SCORES = Array.from({ length: MAX_SCORE - MIN_SCORE + 1 }, (_, index) => MIN_SCORE + index)

const message = { fontSize: 13, color: 'text.secondary' } as const

interface ScaleProps {
  label: 'Moral' | 'Fatigue'
  value: number | null
  onChange: (value: number | null) => void
}

/** Ten radios, 1 to 10; the checked one, clicked again, clears the score. */
const Scale = ({ label, value, onChange }: ScaleProps) => (
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
    <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
      {label}
    </Typography>
    <Box role="radiogroup" aria-label={label} data-testid={`journal-${label === 'Moral' ? 'mood' : 'fatigue'}`} sx={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
      {SCORES.map((score) => {
        const checked = score === value

        return (
          <Box
            key={score}
            component="button"
            type="button"
            role="radio"
            aria-checked={checked}
            aria-label={String(score)}
            onClick={() => onChange(checked ? null : score)}
            sx={(theme) => ({
              appearance: 'none',
              border: 0,
              m: 0,
              font: 'inherit',
              cursor: 'pointer',
              width: 32,
              height: 32,
              borderRadius: 2,
              fontSize: 13,
              fontVariantNumeric: 'tabular-nums',
              bgcolor: checked ? theme.palette.primary.main : theme.palette.veilleuse.raised,
              color: checked ? theme.palette.primary.contrastText : theme.palette.text.primary,
            })}
          >
            {score}
          </Box>
        )
      })}
    </Box>
  </Box>
)

interface NoteProps {
  note: string | null
  onSave: (note: string | null) => Promise<boolean>
}

/** « Note » and « Enregistrer »: kept as typed until saved. */
const NoteEditor = ({ note, onSave }: NoteProps) => {
  const [draft, setDraft] = useState(note ?? '')
  const [saving, setSaving] = useState(false)
  const unchanged = draft.trim() === (note ?? '').trim()

  return (
    <Box
      component="form"
      onSubmit={async (event: FormEvent) => {
        event.preventDefault()
        setSaving(true)
        await onSave(draft.trim() === '' ? null : draft.trim())
        setSaving(false)
      }}
      sx={{ display: 'flex', flexDirection: 'column', gap: 1, alignItems: 'flex-end' }}
    >
      <TextField
        label="Note"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        multiline
        minRows={3}
        fullWidth
        slotProps={{ htmlInput: { maxLength: NOTE_MAX_LENGTH } }}
      />
      <Button type="submit" variant="contained" size="small" disabled={unchanged || saving}>
        Enregistrer
      </Button>
    </Box>
  )
}

interface IndicatorProps {
  label: string
  value: string
  detail?: string
  to: string
  testId: string
}

/** One figure of the day, a link to the section it comes from. */
const Indicator = ({ label, value, detail, to, testId }: IndicatorProps) => (
  <Box
    component={RouterLink}
    to={to}
    data-testid={`journal-${testId}`}
    sx={(theme) => ({
      display: 'flex',
      flexDirection: 'column',
      gap: 0.25,
      px: 1.5,
      py: 1,
      borderRadius: 2.5,
      bgcolor: theme.palette.veilleuse.raised,
      color: 'inherit',
      textDecoration: 'none',
      minWidth: 0,
    })}
  >
    <Typography component="span" sx={{ fontSize: 12, color: 'text.secondary' }}>
      {label}
    </Typography>
    <Typography component="span" data-testid={`journal-${testId}-value`} sx={{ fontSize: 18, fontWeight: 300 }}>
      {value}
    </Typography>
    {detail && (
      <Typography component="span" data-testid={`journal-${testId}-detail`} sx={{ fontSize: 12, color: 'text.secondary' }}>
        {detail}
      </Typography>
    )}
  </Box>
)

interface JournalEntryCardProps {
  day: string
  today: string
  entry: Loaded<HealthDay>
  onDay: (day: string) => void
  onPatch: (patch: HealthDayPatch) => Promise<boolean>
}

/**
 * The chosen day: ‹ the day ›, the scales Moral and Fatigue, who noted them,
 * the free note, and the day's figures, each opening its section.
 */
export const JournalEntryCard = ({ day, today, entry, onDay, onPatch }: JournalEntryCardProps) => {
  const data = entry.status === 'ready' && entry.data.date === day ? entry.data : null

  return (
    <Box component="section" aria-label="Entrée du jour" sx={{ ...cardSx, gap: 1.75 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        <IconButton size="small" aria-label="Jour précédent" onClick={() => onDay(shiftDay(day, -1))}>
          <ChevronLeftIcon fontSize="small" />
        </IconButton>
        <Typography component="h2" data-testid="journal-day" sx={{ fontSize: 15, fontWeight: 500, flexGrow: 1, textAlign: 'center' }}>
          {journalDayLabel(day, today)}
        </Typography>
        <IconButton size="small" aria-label="Jour suivant" disabled={day >= today} onClick={() => onDay(shiftDay(day, 1))}>
          <ChevronRightIcon fontSize="small" />
        </IconButton>
      </Box>
      {entry.status === 'error' && <Typography sx={message}>Erreur : le jour n’a pas pu être chargé.</Typography>}
      {data === null && entry.status !== 'error' && <Typography sx={message}>Chargement…</Typography>}
      {data !== null && (
        <>
          <Scale label="Moral" value={data.mood} onChange={(mood) => void onPatch({ mood })} />
          <Scale label="Fatigue" value={data.fatigue} onChange={(fatigue) => void onPatch({ fatigue })} />
          {data.notedBy === 'maggie' && data.notedAt !== null && (
            <Typography component="span" data-testid="journal-noted-by" sx={{ fontSize: 12, color: 'text.secondary' }}>
              avec Maggie · {formatClock(data.notedAt)}
            </Typography>
          )}
          <NoteEditor key={`${data.date}|${data.note ?? ''}`} note={data.note} onSave={(note) => onPatch({ note })} />
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
            <Indicator
              label="Poids"
              value={formatWeight(data.weightKg)}
              detail={data.weighedAt === null ? undefined : `pesé à ${formatClock(data.weighedAt)}`}
              to={HEALTH_SUIVI_PATH}
              testId="weight"
            />
            <Indicator label="Pas" value={formatSteps(data.steps)} to={HEALTH_SUIVI_PATH} testId="steps" />
            <Indicator label="Bilan" value={formatBalance(data.balanceKcal)} to={HEALTH_NUTRITION_PATH} testId="balance" />
            <Indicator label="Sport" value={formatMinutes(data.sportMinutes)} to={HEALTH_SPORT_PATH} testId="sport" />
          </Box>
        </>
      )}
    </Box>
  )
}
