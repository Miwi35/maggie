import { useState } from 'react'
import { useNotify } from 'react-admin'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { parseDay } from '../../dates'
import { createRoutine, deleteRoutine, updateRoutine, WEEKDAYS } from './routinesApi'
import type { Anchor, Recurrence, RecurrenceKind, Routine, RoutineFields } from './routinesApi'

const ANCHOR_LABELS: Record<Anchor, string> = {
  wake: 'Au réveil',
  afterBreakfast: 'Après le petit-déj',
  afterLunch: 'Après le déjeuner',
  afterDinner: 'Après le dîner',
  time: 'À une heure',
}

const DEFAULT_TIME = '08:00'

interface RoutineDialogProps {
  open: boolean
  /** The routine changed, or none for a new one. */
  routine: Routine | null
  /** Today, `YYYY-MM-DD`: a new weekly routine falls on its weekday, a monthly one on its date. */
  today: string
  onClose: () => void
  /** A routine written or deleted: the list reads again. */
  onSaved: () => void
}

/** The recurrence as the API takes it: the day only where the rhythm needs one. */
const recurrenceOf = (kind: RecurrenceKind, weekday: number, monthDay: number): Recurrence => {
  if (kind === 'weekly') return { kind, weekday }
  if (kind === 'monthly') return { kind, monthDay }

  return { kind }
}

const sameRecurrence = (a: Recurrence, b: Recurrence): boolean =>
  a.kind === b.kind && (a.weekday ?? null) === (b.weekday ?? null) && (a.monthDay ?? null) === (b.monthDay ?? null)

/**
 * « Nouvelle routine », or a routine opened from its card (MAG-511): what, when,
 * how often, and whether Maggie reminds it. A change sends only the fields it moved;
 * « Supprimer » asks once more before it goes.
 */
export const RoutineDialog = ({ open, routine, today, onClose, onSaved }: RoutineDialogProps) => {
  const notify = useNotify()

  // The form starts from the routine opened; the list mounts it anew at each opening.
  const day = parseDay(today)
  const [title, setTitle] = useState(routine?.title ?? '')
  const [anchor, setAnchor] = useState<Anchor>(routine?.anchor ?? 'wake')
  const [anchorTime, setAnchorTime] = useState(routine?.anchorTime ?? DEFAULT_TIME)
  const [kind, setKind] = useState<RecurrenceKind>(routine?.recurrence.kind ?? 'daily')
  // Monday is 1, Sunday 7.
  const [weekday, setWeekday] = useState(routine?.recurrence.weekday ?? ((day.getDay() + 6) % 7) + 1)
  const [monthDay, setMonthDay] = useState(routine?.recurrence.monthDay ?? day.getDate())
  const [remind, setRemind] = useState(routine?.remind ?? false)
  const [active, setActive] = useState(routine?.active ?? true)
  const [titleError, setTitleError] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const fail = (error: Error) => notify(`Erreur : ${error.message}`, { type: 'info' })

  const done = () => {
    onSaved()
    onClose()
  }

  const fields = (): RoutineFields => ({
    title: title.trim(),
    anchor,
    anchorTime: anchor === 'time' ? anchorTime : null,
    recurrence: recurrenceOf(kind, weekday, monthDay),
    remind,
  })

  /** What moved since the routine was opened: the PATCH carries that and nothing else. */
  const changes = (current: Routine): Partial<RoutineFields> => {
    const next = fields()
    const patch: Partial<RoutineFields> = {}
    if (next.title !== current.title) patch.title = next.title
    if (next.anchor !== current.anchor || (next.anchorTime ?? null) !== (current.anchorTime ?? null)) {
      patch.anchor = next.anchor
      patch.anchorTime = next.anchorTime
    }
    if (!sameRecurrence(next.recurrence, current.recurrence)) patch.recurrence = next.recurrence
    if (remind !== current.remind) patch.remind = remind
    if (active !== current.active) patch.active = active

    return patch
  }

  const save = () => {
    if (!title.trim()) {
      setTitleError(true)
      return
    }
    if (anchor === 'time' && !/^\d{2}:\d{2}$/.test(anchorTime)) return

    let request: Promise<unknown>
    if (routine) {
      const patch = changes(routine)
      if (Object.keys(patch).length === 0) {
        onClose()
        return
      }
      request = updateRoutine(routine.id, patch)
    } else {
      request = createRoutine(fields())
    }

    setSubmitting(true)
    request
      .then(done)
      .catch(fail)
      .finally(() => setSubmitting(false))
  }

  const remove = () => {
    if (!routine) return
    setSubmitting(true)
    deleteRoutine(routine.id)
      .then(done)
      .catch(fail)
      .finally(() => setSubmitting(false))
  }

  const weekdayLabel = `Chaque ${WEEKDAYS[weekday - 1]}`

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="routine-dialog-title">
      <DialogTitle id="routine-dialog-title">{routine ? routine.title : 'Nouvelle routine'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} sx={{ mt: 1 }}>
          <TextField
            label="Quoi"
            value={title}
            onChange={(e) => {
              setTitle(e.target.value)
              setTitleError(false)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                save()
              }
            }}
            error={titleError}
            required
            fullWidth
            autoFocus
            slotProps={{ htmlInput: { maxLength: 255 } }}
          />

          <Stack spacing={1}>
            <Typography variant="subtitle2" id="routine-when">
              Quand
            </Typography>
            <ToggleButtonGroup
              value={anchor}
              exclusive
              onChange={(_, next: Anchor | null) => next && setAnchor(next)}
              aria-labelledby="routine-when"
              size="small"
              sx={{ flexWrap: 'wrap', gap: 1, '& .MuiToggleButton-root': { borderRadius: 4, border: 1, borderColor: 'divider' } }}
            >
              {(Object.keys(ANCHOR_LABELS) as Anchor[]).map((value) => (
                <ToggleButton key={value} value={value} sx={{ textTransform: 'none' }}>
                  {ANCHOR_LABELS[value]}
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
            {anchor === 'time' && (
              <TextField
                label="Heure"
                type="time"
                size="small"
                value={anchorTime}
                onChange={(e) => setAnchorTime(e.target.value)}
                sx={{ maxWidth: 160 }}
                slotProps={{ inputLabel: { shrink: true } }}
              />
            )}
          </Stack>

          <Stack spacing={1}>
            <Typography variant="subtitle2" id="routine-repeat">
              Répéter
            </Typography>
            <ToggleButtonGroup
              value={kind}
              exclusive
              onChange={(_, next: RecurrenceKind | null) => next && setKind(next)}
              aria-labelledby="routine-repeat"
              size="small"
              sx={{ flexWrap: 'wrap', gap: 1, '& .MuiToggleButton-root': { borderRadius: 4, border: 1, borderColor: 'divider' } }}
            >
              <ToggleButton value="daily" sx={{ textTransform: 'none' }}>
                Chaque jour
              </ToggleButton>
              <ToggleButton value="weekdays" sx={{ textTransform: 'none' }}>
                Jours de semaine
              </ToggleButton>
              <ToggleButton value="weekly" sx={{ textTransform: 'none' }}>
                {weekdayLabel}
              </ToggleButton>
              <ToggleButton value="monthly" sx={{ textTransform: 'none' }}>
                Chaque mois
              </ToggleButton>
            </ToggleButtonGroup>
            {kind === 'weekly' && (
              <TextField
                select
                label="Jour"
                size="small"
                value={weekday}
                onChange={(e) => setWeekday(Number(e.target.value))}
                sx={{ maxWidth: 200 }}
              >
                {WEEKDAYS.map((name, i) => (
                  <MenuItem key={name} value={i + 1}>
                    {name}
                  </MenuItem>
                ))}
              </TextField>
            )}
            {kind === 'monthly' && (
              <TextField
                select
                label="Jour du mois"
                size="small"
                value={monthDay}
                onChange={(e) => setMonthDay(Number(e.target.value))}
                sx={{ maxWidth: 200 }}
              >
                {Array.from({ length: 31 }, (_, i) => i + 1).map((n) => (
                  <MenuItem key={n} value={n}>
                    {n === 1 ? '1er' : `le ${n}`}
                  </MenuItem>
                ))}
              </TextField>
            )}
          </Stack>

          <FormControlLabel
            control={<Switch checked={remind} onChange={(e) => setRemind(e.target.checked)} />}
            label="Me le rappeler"
          />
          {routine && (
            <FormControlLabel
              control={<Switch checked={active} onChange={(e) => setActive(e.target.checked)} />}
              label="Active"
            />
          )}
        </Stack>
      </DialogContent>
      {confirming && routine ? (
        <DialogActions>
          <Typography variant="body2" sx={{ flex: 1, pl: 1 }}>
            Supprimer « {routine.title} » ?
          </Typography>
          <Button onClick={() => setConfirming(false)} disabled={submitting}>
            Annuler
          </Button>
          <Button onClick={remove} variant="contained" color="inherit" disabled={submitting}>
            Oui, supprimer
          </Button>
        </DialogActions>
      ) : (
        <DialogActions>
          {routine && (
            <Button onClick={() => setConfirming(true)} color="inherit" sx={{ mr: 'auto' }} disabled={submitting}>
              Supprimer
            </Button>
          )}
          <Button onClick={onClose} disabled={submitting}>
            Annuler
          </Button>
          <Button onClick={save} variant="contained" disabled={submitting}>
            Enregistrer
          </Button>
        </DialogActions>
      )}
    </Dialog>
  )
}
