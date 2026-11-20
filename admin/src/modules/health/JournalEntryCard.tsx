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
import { MaggieAvatar } from '../../components/maggie/MaggieAvatar'
import { HEALTH_NUTRITION_PATH, HEALTH_SUIVI_PATH } from '../../components/layout/navigation'
import { MAX_SCORE, MIN_SCORE } from './healthDaysApi'
import type { HealthDay, HealthDayPatch } from './healthDaysApi'
import { formatBalance, formatClock, formatMinutes, formatSteps, formatWeight, journalDayLabel, journalIntake, journalWeighing } from './healthJournal'
import { cardSx, shiftDay } from './nutritionBalance'
import { NONE } from './suiviSeries'
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
const Scale = ({ label, value, onChange }: ScaleProps) => {
  const testKey = label === 'Moral' ? 'mood' : 'fatigue'

  return (
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
      <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
        {label}
      </Typography>
      <Typography component="span" data-testid={`journal-${testKey}-value`} sx={{ fontSize: 14 }}>
        {value === null ? NONE : `${value} / 10`}
      </Typography>
    </Box>
    <Box role="radiogroup" aria-label={label} data-testid={`journal-${testKey}`} sx={{ display: 'flex', gap: '3px' }}>
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
              flex: '1 1 0',
              minWidth: 0,
              height: 34,
              borderRadius: 2,
              fontSize: 13,
              fontVariantNumeric: 'tabular-nums',
              bgcolor: checked ? theme.palette.module.sport : theme.palette.veilleuse.raised,
              color: checked ? theme.palette.getContrastText(theme.palette.module.sport) : theme.palette.text.secondary,
            })}
          >
            {score}
          </Box>
        )
      })}
    </Box>
  </Box>
  )
}

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
      <Typography component="label" htmlFor="journal-note" sx={{ fontSize: 13, color: 'text.secondary', alignSelf: 'flex-start' }}>
        Note
      </Typography>
      <TextField
        id="journal-note"
        variant="filled"
        hiddenLabel
        placeholder="Note du jour"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        multiline
        minRows={4}
        fullWidth
        sx={(theme) => ({ '& .MuiFilledInput-root': { bgcolor: theme.palette.veilleuse.raised, borderRadius: 3, '&:hover, &.Mui-focused': { bgcolor: theme.palette.veilleuse.raised } } })}
        slotProps={{ input: { disableUnderline: true }, htmlInput: { maxLength: NOTE_MAX_LENGTH } }}
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
      justifyContent: 'space-between',
      alignItems: 'baseline',
      minHeight: 36,
      borderTop: `1px solid ${theme.palette.divider}`,
      fontSize: 13,
      color: 'inherit',
      textDecoration: 'none',
      minWidth: 0,
    })}
  >
    <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
      {label}
    </Typography>
    <Box component="span">
      <Typography component="span" data-testid={`journal-${testId}-value`} sx={{ fontSize: 13, color: 'text.primary' }}>
        {value}
      </Typography>
      {detail && (
        <Typography component="span" data-testid={`journal-${testId}-detail`} sx={{ fontSize: 12, color: 'text.secondary', ml: 0.75 }}>
          {detail}
        </Typography>
      )}
    </Box>
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
      <Box data-testid="journal-header" sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        <Typography component="h2" data-testid="journal-day" sx={{ fontSize: 15, fontWeight: 500, flexGrow: 1, textAlign: 'left' }}>
          {journalDayLabel(day, today)}
        </Typography>
        {data !== null && data.notedBy === 'maggie' && data.notedAt !== null && (
          <Box data-testid="journal-noted-by" sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mr: 0.5, fontSize: 12, color: 'text.secondary' }}>
            <MaggieAvatar size={14} />
            avec Maggie · {formatClock(data.notedAt)}
          </Box>
        )}
        <IconButton size="small" aria-label="Jour précédent" onClick={() => onDay(shiftDay(day, -1))}>
          <ChevronLeftIcon fontSize="small" />
        </IconButton>
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
          <NoteEditor key={`${data.date}|${data.note ?? ''}`} note={data.note} onSave={(note) => onPatch({ note })} />
          <Box sx={{ display: 'flex', flexDirection: 'column', pt: 0.5 }}>
            <Indicator label="Poids" value={formatWeight(data.weightKg)} detail={journalWeighing(data)} to={HEALTH_SUIVI_PATH} testId="weight" />
            <Indicator label="Pas" value={formatSteps(data.steps)} to={HEALTH_SUIVI_PATH} testId="steps" />
            <Indicator label="Bilan calorique" value={formatBalance(data.balanceKcal)} detail={journalIntake(data)} to={HEALTH_NUTRITION_PATH} testId="balance" />
            <Indicator label="Sport" value={formatMinutes(data.sportMinutes)} to={HEALTH_SPORT_PATH} testId="sport" />
          </Box>
        </>
      )}
    </Box>
  )
}
