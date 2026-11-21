import { useEffect, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import InputAdornment from '@mui/material/InputAdornment'
import MenuItem from '@mui/material/MenuItem'
import Popover from '@mui/material/Popover'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import SyncIcon from '@mui/icons-material/Sync'
import { CalendarGrid } from '../../components/form/CalendarGrid'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'
import { ReminderPicker, remindersFromMinutes, remindersToMinutes } from './ReminderPicker'
import {
  REPEAT_CHOICES,
  WEEKDAYS,
  buildRule,
  checkedChoice,
  defaultRule,
  parseRule,
  pickChoice,
  plainRule,
  repeatOf,
  ruleText,
  untilLabel,
  withCustom,
  withEnd,
  type RepeatRule,
  type RepeatState,
  type Weekday,
} from './taskRepeat'

/** A link Google Tasks attached to the task (an email, mostly). Read only (MAG-404). */
export interface TaskLink {
  type?: string
  description?: string
  link: string
}

export type TaskMoment = 'morning' | 'afternoon' | 'evening' | 'time'

export interface TaskData {
  id: string
  title: string
  description?: string
  priority: string
  criticality: string
  /** The due day, `YYYY-MM-DD` — a task has no time (MAG-404). */
  dueDate?: string | null
  /** The day the task is planned for, `YYYY-MM-DD` (MAG-718). */
  scheduledDate?: string | null
  /** When in that day: a part of it, or `time` with `scheduledTime`; null: the day only. */
  scheduledMoment?: TaskMoment | null
  /** `HH:MM`, with the moment `time` only. */
  scheduledTime?: string | null
  /** Minutes before `scheduledTime`, with the moment `time` only. */
  reminders?: number[]
  completedAt?: string | null
  /** The IRI of the parent task; absent on a top-level task. */
  parent?: string | null
  /** The order among siblings, compared as a plain string. */
  position?: string | null
  links?: TaskLink[]
  /** Computed by the API from the criticality and the due day (MAG-406); read only. */
  urgency?: string | null
  /** How long it should take, in minutes (1 to 1440) — Maggie's own (MAG-497). */
  estimatedMinutes?: number | null
  /** The first small gesture that starts it — Maggie's own (MAG-497). */
  firstStep?: string | null
  /** `YYYY-MM-DD`: computed by the API, the day the task has been put off since. */
  postponedSince?: string | null
  /** RFC 5545 without `RRULE:`, carried by the open occurrence; null: it does not repeat (MAG-870). */
  rrule?: string | null
}

/** The bounds of « Durée estimée », the API's own. */
export const MAX_ESTIMATED_MINUTES = 1440

/** The minutes typed in « Durée estimée »: null when empty, NaN when out of bounds. */
export const parseMinutes = (value: string): number | null => {
  if (!value.trim()) return null
  const minutes = Number(value)

  return Number.isInteger(minutes) && minutes >= 1 && minutes <= MAX_ESTIMATED_MINUTES ? minutes : Number.NaN
}

/** React-admin hands back resources as IRIs; `parent` wants one either way. */
export const taskIri = (id: string): string => `/api/tasks/${id.split('/').pop() ?? id}`

/** Siblings by `position` as a string, then by id — the API's own order. */
export const bySiblingOrder = (a: TaskData, b: TaskData): number => {
  const pa = a.position ?? ''
  const pb = b.position ?? ''
  if (pa !== pb) return pa < pb ? -1 : 1
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

/** The words of the moments, for the form and the sheet alike. */
export const MOMENT_LABELS: Record<Exclude<TaskMoment, 'time'>, string> = {
  morning: 'Matin',
  afternoon: 'Après-midi',
  evening: 'Soir',
}

type Mode = 'day' | TaskMoment

const LEVELS = [
  { value: 'low', label: 'Basse' },
  { value: 'medium', label: 'Moyenne' },
  { value: 'high', label: 'Haute' },
]

const DAY_BUTTONS = ['L', 'M', 'M', 'J', 'V', 'S', 'D']

const CRITICALITIES = [...LEVELS, { value: 'critical', label: 'Critique' }]

interface EstimateFieldsProps {
  minutes: string
  onMinutes: (value: string) => void
  firstStep: string
  onFirstStep: (value: string) => void
}

/** « Durée estimée » and « Premier pas » (MAG-497). */
export const EstimateFields = ({ minutes, onMinutes, firstStep, onFirstStep }: EstimateFieldsProps) => {
  const invalid = Number.isNaN(parseMinutes(minutes))

  return (
    <>
      <TextField
        label="Durée estimée"
        type="number"
        value={minutes}
        onChange={(e) => onMinutes(e.target.value)}
        error={invalid}
        helperText={invalid ? `Entre 1 et ${MAX_ESTIMATED_MINUTES} minutes` : undefined}
        slotProps={{
          htmlInput: { min: 1, max: MAX_ESTIMATED_MINUTES, step: 1 },
          input: { endAdornment: <InputAdornment position="end">min</InputAdornment> },
        }}
      />
      <TextField
        label="Premier pas"
        value={firstStep}
        onChange={(e) => onFirstStep(e.target.value)}
        slotProps={{ htmlInput: { maxLength: 255 } }}
      />
    </>
  )
}

interface SegmentedProps {
  label: string
  value: string
  options: { value: string; label: string }[]
  onChange: (value: string) => void
}

const Segmented = ({ label, value, options, onChange }: SegmentedProps) => (
  <Stack spacing={0.5}>
    <Typography variant="caption" color="text.secondary">
      {label}
    </Typography>
    <ToggleButtonGroup
      exclusive
      size="small"
      fullWidth
      aria-label={label}
      value={value}
      onChange={(_, next: string | null) => next && onChange(next)}
    >
      {options.map((option) => (
        <ToggleButton key={option.value} value={option.value}>
          {option.label}
        </ToggleButton>
      ))}
    </ToggleButtonGroup>
  </Stack>
)

interface TaskFormDialogProps {
  open: boolean
  /** The task to change; null to create one. */
  task: TaskData | null
  /** The due day a new task starts with, `YYYY-MM-DD`. */
  defaultDueDate?: string
  onClose: () => void
  /** After a create, a change or a delete: the caller reloads what it shows. */
  onSaved: () => void
}

/**
 * The one form of a task, « Nouvelle tâche » or « Modifier la tâche » (MAG-723). The sheet beside the list
 * only reads; every field is written here.
 */
export const TaskFormDialog = ({ open, task, defaultDueDate, onClose, onSaved }: TaskFormDialogProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const editing = task !== null

  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('medium')
  const [criticality, setCriticality] = useState('low')
  const [dueDate, setDueDate] = useState('')
  const [scheduledDate, setScheduledDate] = useState('')
  const [mode, setMode] = useState<Mode>('day')
  const [time, setTime] = useState('09:00')
  const [reminders, setReminders] = useState<number[]>([])
  const [minutes, setMinutes] = useState('')
  const [firstStep, setFirstStep] = useState('')
  const [isDone, setIsDone] = useState(false)
  const [titleError, setTitleError] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [repeat, setRepeat] = useState<RepeatState>(repeatOf(null, null))
  const [customDraft, setCustomDraft] = useState<RepeatRule | null>(null)
  const [customBefore, setCustomBefore] = useState<RepeatState | null>(null)
  const [customOpen, setCustomOpen] = useState(false)
  const [untilAnchor, setUntilAnchor] = useState<HTMLElement | null>(null)

  const anchor = scheduledDate || dueDate || null
  const [repeatField, setRepeatField] = useState<HTMLDivElement | null>(null)

  const openCustom = () => {
    if (!anchor) return
    setCustomBefore(repeat)
    setCustomDraft(parseRule(buildRule(repeat, anchor)) ?? defaultRule(anchor))
    setCustomOpen(true)
  }

  const closeCustom = (confirmed: boolean) => {
    if (confirmed && customDraft) setRepeat(withCustom(repeat, customDraft))
    else if (customBefore) setRepeat(customBefore)
    setCustomOpen(false)
  }

  useEffect(() => {
    if (!open) return
    setTitle(task?.title ?? '')
    setDescription(task?.description || '')
    setPriority(task?.priority ?? 'medium')
    setCriticality(task?.criticality ?? 'low')
    // A day already; the slice still copes with a row cached before MAG-404.
    setDueDate(task ? (task.dueDate ? task.dueDate.slice(0, 10) : '') : (defaultDueDate ?? ''))
    setScheduledDate(task?.scheduledDate ? task.scheduledDate.slice(0, 10) : '')
    setMode(task?.scheduledMoment ?? 'day')
    setTime(task?.scheduledTime ?? '09:00')
    setReminders(task?.reminders ?? [])
    setMinutes(task?.estimatedMinutes != null ? String(task.estimatedMinutes) : '')
    setFirstStep(task?.firstStep ?? '')
    setIsDone(task?.completedAt != null)
    setTitleError(false)
    setConfirmDelete(false)
    const day = task ? task.scheduledDate?.slice(0, 10) || task.dueDate?.slice(0, 10) || null : (defaultDueDate ?? null)
    setRepeat(repeatOf(task?.rrule, day))
    setCustomOpen(false)
    setUntilAnchor(null)
  }, [open, task, defaultDueDate])

  const fail = (error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' })

  const handleSubmit = () => {
    if (!title.trim()) {
      setTitleError(true)
      return
    }
    const estimatedMinutes = parseMinutes(minutes)
    if (Number.isNaN(estimatedMinutes)) return
    const precise = !!scheduledDate && mode === 'time'
    if (precise && !time) return

    const rule = buildRule(repeat, anchor)

    const schedule = {
      scheduledDate: scheduledDate || null,
      scheduledMoment: scheduledDate && mode !== 'day' ? mode : null,
      scheduledTime: precise ? time : null,
      reminders: precise ? reminders : [],
    }

    setSubmitting(true)

    const saved = task
      ? dataProvider.update('tasks', {
          id: task.id,
          data: {
            title: title.trim(),
            description: description.trim() || null,
            priority,
            criticality,
            dueDate: dueDate || null,
            estimatedMinutes,
            firstStep: firstStep.trim() || null,
            completedAt: isDone ? (task.completedAt ?? new Date().toISOString()) : null,
            rrule: rule,
            ...schedule,
          },
          previousData: task,
        })
      : dataProvider.create('tasks', {
          data: {
            title: title.trim(),
            ...(description.trim() ? { description: description.trim() } : {}),
            priority,
            criticality,
            // A plain day, the field's own type: no zone can move it (MAG-404).
            ...(dueDate ? { dueDate } : {}),
            ...(estimatedMinutes != null ? { estimatedMinutes } : {}),
            ...(firstStep.trim() ? { firstStep: firstStep.trim() } : {}),
            ...(scheduledDate ? schedule : {}),
            ...(rule ? { rrule: rule } : {}),
          },
        })

    saved
      .then(() => {
        notify(editing ? 'Tâche mise à jour' : 'Tâche créée', { type: 'success' })
        onSaved()
        onClose()
      })
      .catch(fail)
      .finally(() => setSubmitting(false))
  }

  const handleDelete = () => {
    if (!task) return
    if (!confirmDelete) {
      setConfirmDelete(true)
      return
    }
    setSubmitting(true)
    dataProvider
      .delete('tasks', { id: task.id, previousData: task })
      .then(() => {
        notify('Tâche supprimée', { type: 'success' })
        onSaved()
        onClose()
      })
      .catch(fail)
      .finally(() => setSubmitting(false))
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{editing ? 'Modifier la tâche' : 'Nouvelle tâche'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Titre"
            value={title}
            onChange={(e) => {
              setTitle(e.target.value)
              if (e.target.value.trim()) setTitleError(false)
            }}
            error={titleError}
            helperText={titleError ? 'Le titre est requis' : undefined}
            required
            autoFocus
          />
          <TextField
            label="Échéance"
            type="date"
            value={dueDate}
            onChange={(e) => setDueDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="Programmée"
            type="date"
            value={scheduledDate}
            onChange={(e) => setScheduledDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          {scheduledDate && (
            <>
              <Segmented
                label="Moment"
                value={mode}
                onChange={(next) => setMode(next as Mode)}
                options={[
                  { value: 'day', label: 'Journée' },
                  { value: 'morning', label: MOMENT_LABELS.morning },
                  { value: 'afternoon', label: MOMENT_LABELS.afternoon },
                  { value: 'evening', label: MOMENT_LABELS.evening },
                  { value: 'time', label: 'Heure précise' },
                ]}
              />
              {mode === 'time' && (
                <>
                  <TextField
                    label="Heure"
                    type="time"
                    value={time}
                    onChange={(e) => setTime(e.target.value)}
                    required
                    slotProps={{ inputLabel: { shrink: true } }}
                  />
                  <ReminderPicker
                    value={remindersFromMinutes(reminders)}
                    onChange={(next) => setReminders(remindersToMinutes(next))}
                  />
                </>
              )}
            </>
          )}
          {!task?.parent && (
            <TextField
              label="Répéter"
              select
              ref={setRepeatField}
              value={checkedChoice(repeat, anchor)}
              disabled={!anchor}
              onChange={(e) => {
                const choice = e.target.value as (typeof REPEAT_CHOICES)[number]['key']
                if (choice === 'custom') return
                setRepeat(pickChoice(repeat, choice))
              }}
              slotProps={{
                select: {
                  renderValue: () => ruleText(buildRule(repeat, anchor), anchor),
                },
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <SyncIcon fontSize="small" />
                    </InputAdornment>
                  ),
                },
              }}
            >
              {REPEAT_CHOICES.map((choice) => (
                <MenuItem
                  key={choice.key}
                  value={choice.key}
                  onClick={choice.key === 'custom' ? openCustom : undefined}
                >
                  {choice.label}
                </MenuItem>
              ))}
            </TextField>
          )}
          {!task?.parent && anchor && buildRule(repeat, anchor) && (
            <ButtonBase
              aria-label={`Jusqu’au : ${untilLabel(repeat.until)}`}
              onClick={(e) => setUntilAnchor(e.currentTarget)}
              sx={{ justifyContent: 'space-between', border: 1, borderColor: 'divider', borderRadius: 1, px: 1.75, py: 1.5 }}
            >
              <Typography variant="body2" color="text.secondary">
                Jusqu’au
              </Typography>
              <Typography variant="body1">{untilLabel(repeat.until)}</Typography>
            </ButtonBase>
          )}
          <Segmented label="Criticité" value={criticality} options={CRITICALITIES} onChange={setCriticality} />
          <Segmented label="Priorité" value={priority} options={LEVELS} onChange={setPriority} />
          <MarkdownEditor label="Notes" value={description} onChange={setDescription} />
          <EstimateFields minutes={minutes} onMinutes={setMinutes} firstStep={firstStep} onFirstStep={setFirstStep} />
          {editing && (
            <FormControlLabel
              control={<Switch checked={isDone} onChange={(e) => setIsDone(e.target.checked)} />}
              label="Terminée"
            />
          )}
        </Stack>
      </DialogContent>
      <Popover
        open={customOpen}
        anchorEl={repeatField}
        onClose={() => closeCustom(false)}
        slotProps={{ paper: { 'aria-label': 'Répétition personnalisée', role: 'dialog' } as object }}
      >
        {customDraft && (
          <Stack spacing={2} sx={{ p: 2, width: 320 }}>
            <Typography variant="subtitle2">Répétition personnalisée</Typography>
            <Stack direction="row" spacing={1} alignItems="center">
              <Typography variant="body2">{customDraft.unit === 'week' ? 'Toutes les' : 'Tous les'}</Typography>
              <TextField
                type="number"
                size="small"
                value={customDraft.interval}
                onChange={(e) =>
                  setCustomDraft({ ...customDraft, interval: Math.max(1, Math.floor(Number(e.target.value)) || 1) })
                }
                slotProps={{ htmlInput: { min: 1, 'aria-label': 'Intervalle' } }}
                sx={{ width: 80 }}
              />
              <ToggleButtonGroup
                exclusive
                size="small"
                aria-label="Unité"
                value={customDraft.unit}
                onChange={(_, unit: RepeatRule['unit'] | null) =>
                  unit &&
                  setCustomDraft({
                    ...customDraft,
                    unit,
                    days: unit === 'week' ? (customDraft.days.length ? customDraft.days : defaultRule(anchor ?? '').days) : [],
                    monthDay: null,
                  })
                }
              >
                <ToggleButton value="day">jours</ToggleButton>
                <ToggleButton value="week">semaines</ToggleButton>
                <ToggleButton value="month">mois</ToggleButton>
              </ToggleButtonGroup>
            </Stack>
            {customDraft.unit === 'week' && (
              <ToggleButtonGroup
                size="small"
                aria-label="Jours"
                value={customDraft.days}
                onChange={(_, days: Weekday[]) => days.length > 0 && setCustomDraft({ ...customDraft, days })}
              >
                {WEEKDAYS.map((day, i) => (
                  <ToggleButton key={day.code} value={day.code} aria-label={day.name}>
                    {DAY_BUTTONS[i]}
                  </ToggleButton>
                ))}
              </ToggleButtonGroup>
            )}
            <Typography variant="body2" color="text.secondary">
              {plainRule(customDraft, anchor)}
            </Typography>
            <Stack direction="row" spacing={1} justifyContent="flex-end">
              <Button onClick={() => closeCustom(false)}>Annuler</Button>
              <Button variant="contained" onClick={() => closeCustom(true)}>
                OK
              </Button>
            </Stack>
          </Stack>
        )}
      </Popover>
      <Popover
        open={untilAnchor !== null}
        anchorEl={untilAnchor}
        onClose={() => setUntilAnchor(null)}
        slotProps={{ paper: { 'aria-label': 'Choisir la fin de la répétition', role: 'dialog' } as object }}
      >
        <Stack spacing={1} sx={{ p: 2, width: 280 }}>
          <Typography variant="subtitle2">Choisir la fin de la répétition</Typography>
          <Button
            variant={repeat.until ? 'outlined' : 'contained'}
            onClick={() => {
              setRepeat(withEnd(repeat, null))
              setUntilAnchor(null)
            }}
          >
            Aucune fin
          </Button>
          <CalendarGrid
            value={repeat.until}
            fallbackMonth={anchor ?? undefined}
            onChange={(day) => {
              setRepeat(withEnd(repeat, day))
              setUntilAnchor(null)
            }}
          />
        </Stack>
      </Popover>
      <DialogActions sx={{ justifyContent: 'space-between' }}>
        {editing ? (
          <Button color="error" onClick={handleDelete} disabled={submitting}>
            {confirmDelete ? 'Confirmer la suppression' : 'Supprimer'}
          </Button>
        ) : (
          <span />
        )}
        <Stack direction="row" spacing={1}>
          <Button onClick={onClose}>Annuler</Button>
          <Button onClick={handleSubmit} variant="contained" disabled={submitting}>
            {editing ? 'Enregistrer' : 'Créer'}
          </Button>
        </Stack>
      </DialogActions>
    </Dialog>
  )
}
