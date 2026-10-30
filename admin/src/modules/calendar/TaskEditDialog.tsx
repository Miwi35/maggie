import { useEffect, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Link from '@mui/material/Link'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import MenuItem from '@mui/material/MenuItem'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import CloseIcon from '@mui/icons-material/Close'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'
import { MarkdownText } from '../../components/markdown/MarkdownText'
import { checkboxCount } from '../../components/markdown/markdownEdit'
import { UrgencyBadge } from './UrgencyBadge'

/** A link Google Tasks attached to the task (an email, mostly). Read only (MAG-404). */
export interface TaskLink {
  type?: string
  description?: string
  link: string
}

export interface TaskData {
  id: string
  title: string
  description?: string
  priority: string
  criticality: string
  /** The due day, `YYYY-MM-DD` — a task has no time (MAG-404). */
  dueDate?: string | null
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
}

/** The bounds of « Durée estimée », the API's own. */
export const MAX_ESTIMATED_MINUTES = 1440

/** The minutes typed in « Durée estimée »: null when empty, NaN when out of bounds. */
export const parseMinutes = (value: string): number | null => {
  if (!value.trim()) return null
  const minutes = Number(value)

  return Number.isInteger(minutes) && minutes >= 1 && minutes <= MAX_ESTIMATED_MINUTES ? minutes : Number.NaN
}

interface TaskEditDialogProps {
  open: boolean
  task: TaskData | null
  onClose: () => void
  onUpdated: () => void
  /** `panel`: the sheet beside the list, 420 px wide (MAG-644); `dialog`: over the page. */
  variant?: 'dialog' | 'panel'
}

interface EstimateFieldsProps {
  minutes: string
  onMinutes: (value: string) => void
  firstStep: string
  onFirstStep: (value: string) => void
}

/** « Durée estimée » and « Premier pas », the same in the create and the edit form (MAG-497). */
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

/** React-admin hands back resources as IRIs; `parent` wants one either way. */
export const taskIri = (id: string): string => `/api/tasks/${id.split('/').pop() ?? id}`

/** Siblings by `position` as a string, then by id — the API's own order. */
export const bySiblingOrder = (a: TaskData, b: TaskData): number => {
  const pa = a.position ?? ''
  const pb = b.position ?? ''
  if (pa !== pb) return pa < pb ? -1 : 1
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

export const TaskEditDialog = ({ open, task, onClose, onUpdated, variant = 'dialog' }: TaskEditDialogProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('medium')
  const [criticality, setCriticality] = useState('low')
  const [dueDate, setDueDate] = useState('')
  const [minutes, setMinutes] = useState('')
  const [firstStep, setFirstStep] = useState('')
  const [isDone, setIsDone] = useState(false)
  const [titleError, setTitleError] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [subtasks, setSubtasks] = useState<TaskData[]>([])
  const [newSubtask, setNewSubtask] = useState('')
  const [busy, setBusy] = useState(false)

  // One level only: a subtask has no subtasks of its own.
  const hasSubtasks = task != null && !task.parent

  useEffect(() => {
    if (!open || !task) return
    setTitle(task.title)
    setDescription(task.description || '')
    setPriority(task.priority)
    setCriticality(task.criticality)
    // A day already; the slice still copes with a row cached before MAG-404.
    setDueDate(task.dueDate ? task.dueDate.slice(0, 10) : '')
    setMinutes(task.estimatedMinutes != null ? String(task.estimatedMinutes) : '')
    setFirstStep(task.firstStep ?? '')
    setIsDone(task.completedAt != null)
    setTitleError(false)
    setSubtasks([])
    setNewSubtask('')
  }, [open, task])

  useEffect(() => {
    if (!open || !task || task.parent) return
    let current = true
    dataProvider
      .getList('tasks', {
        pagination: { page: 1, perPage: 100 },
        sort: { field: 'position', order: 'ASC' },
        filter: { parent: taskIri(task.id) },
      })
      .then(({ data }) => {
        if (current) setSubtasks([...(data as unknown as TaskData[])].sort(bySiblingOrder))
      })
      .catch((error: Error) => {
        if (current) notify(`Erreur: ${error.message}`, { type: 'error' })
      })

    return () => {
      current = false
    }
  }, [open, task, dataProvider, notify])

  const fail = (error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' })

  // The list is kept locally after each write rather than refetched: the
  // collection is read from Elasticsearch, which lags the write by a moment.
  const toggleSubtask = (subtask: TaskData, checked: boolean) => {
    const completedAt = checked ? new Date().toISOString() : null
    const set = (value: string | null) =>
      setSubtasks((list) => list.map((s) => (s.id === subtask.id ? { ...s, completedAt: value } : s)))
    set(completedAt)
    dataProvider
      .update('tasks', { id: subtask.id, data: { completedAt }, previousData: subtask })
      .catch((error: Error) => {
        set(subtask.completedAt ?? null)
        fail(error)
      })
  }

  // Moving is one PATCH on the moved subtask with its neighbour's position: the API
  // swaps the two in one operation (and moves the task on Google), then publishes both.
  const moveSubtask = (index: number, offset: -1 | 1) => {
    const a = subtasks[index]
    const b = subtasks[index + offset]
    if (!a || !b) return
    setBusy(true)
    dataProvider
      .update('tasks', { id: a.id, data: { position: b.position ?? null }, previousData: a })
      .then(() => {
        setSubtasks((list) => {
          const next = [...list]
          next[index] = { ...b, position: a.position }
          next[index + offset] = { ...a, position: b.position }
          return next
        })
      })
      .catch(fail)
      .finally(() => setBusy(false))
  }

  const tickBox = (next: string) => {
    if (!task) return
    setDescription(next)
    dataProvider
      .update('tasks', { id: task.id, data: { description: next.trim() || null }, previousData: task })
      .then(() => onUpdated())
      .catch(fail)
  }

  const addSubtask = () => {
    if (!task || !newSubtask.trim()) return
    setBusy(true)
    dataProvider
      .create('tasks', { data: { title: newSubtask.trim(), parent: taskIri(task.id) } })
      .then(({ data }) => {
        // The API puts it last when no position is sent.
        setSubtasks((list) => [...list, data as unknown as TaskData])
        setNewSubtask('')
      })
      .catch(fail)
      .finally(() => setBusy(false))
  }

  const handleSubmit = () => {
    if (!task) return
    if (!title.trim()) {
      setTitleError(true)
      return
    }
    const estimatedMinutes = parseMinutes(minutes)
    if (Number.isNaN(estimatedMinutes)) return

    setSubmitting(true)

    dataProvider
      .update('tasks', {
        id: task.id,
        data: {
          title: title.trim(),
          description: description.trim() || null,
          priority,
          criticality,
          dueDate: dueDate || null,
          estimatedMinutes,
          firstStep: firstStep.trim() || null,
          completedAt: isDone && !task.completedAt ? new Date().toISOString() : isDone ? task.completedAt : null,
        },
        previousData: task,
      })
      .then(() => {
        notify('Tâche mise à jour', { type: 'success' })
        onUpdated()
        onClose()
      })
      .catch(fail)
      .finally(() => setSubmitting(false))
  }

  const links = task?.links ?? []

  const fields = (
        <Stack spacing={2} sx={{ mt: 1 }}>
          {task?.urgency && (
            <Typography variant="body2" color="text.secondary" sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              Urgence <UrgencyBadge urgency={task.urgency} />
            </Typography>
          )}
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
          {checkboxCount(description).total > 0 ? <MarkdownText value={description} onChange={tickBox} /> : null}
          <MarkdownEditor label="Notes" value={description} onChange={setDescription} />
          <TextField
            label="Priorité"
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
            select
          >
            <MenuItem value="low">Basse</MenuItem>
            <MenuItem value="medium">Moyenne</MenuItem>
            <MenuItem value="high">Haute</MenuItem>
          </TextField>
          <TextField
            label="Criticité"
            value={criticality}
            onChange={(e) => setCriticality(e.target.value)}
            select
          >
            <MenuItem value="low">Basse</MenuItem>
            <MenuItem value="medium">Moyenne</MenuItem>
            <MenuItem value="high">Haute</MenuItem>
            <MenuItem value="critical">Critique</MenuItem>
          </TextField>
          <TextField
            label="Échéance"
            type="date"
            value={dueDate}
            onChange={(e) => setDueDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <EstimateFields minutes={minutes} onMinutes={setMinutes} firstStep={firstStep} onFirstStep={setFirstStep} />
          <FormControlLabel
            control={<Switch checked={isDone} onChange={(e) => setIsDone(e.target.checked)} />}
            label="Terminée"
          />

          {links.length > 0 && (
            <Box component="section" aria-label="Liens">
              <Typography variant="subtitle2">Liens</Typography>
              <List dense disablePadding>
                {links.map((l) => (
                  <ListItem key={l.link} disableGutters>
                    <Link href={l.link} target="_blank" rel="noreferrer">
                      {l.description || l.link}
                    </Link>
                  </ListItem>
                ))}
              </List>
            </Box>
          )}

          {hasSubtasks && (
            <Box component="section" aria-label="Sous-tâches">
              <Typography variant="subtitle2">Sous-tâches</Typography>
              <List dense disablePadding>
                {subtasks.map((s, index) => (
                  <ListItem
                    key={s.id}
                    disableGutters
                    secondaryAction={
                      <>
                        <IconButton
                          size="small"
                          aria-label={`Monter ${s.title}`}
                          disabled={busy || index === 0}
                          onClick={() => moveSubtask(index, -1)}
                        >
                          <ArrowUpwardIcon fontSize="small" />
                        </IconButton>
                        <IconButton
                          size="small"
                          aria-label={`Descendre ${s.title}`}
                          disabled={busy || index === subtasks.length - 1}
                          onClick={() => moveSubtask(index, 1)}
                        >
                          <ArrowDownwardIcon fontSize="small" />
                        </IconButton>
                      </>
                    }
                  >
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={s.completedAt != null}
                          onChange={(e) => toggleSubtask(s, e.target.checked)}
                        />
                      }
                      label={s.title}
                      sx={{
                        textDecoration: s.completedAt != null ? 'line-through' : 'none',
                        // Leave room for the two arrows on the right.
                        pr: 9,
                      }}
                    />
                  </ListItem>
                ))}
              </List>
              <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                <TextField
                  label="Nouvelle sous-tâche"
                  size="small"
                  value={newSubtask}
                  onChange={(e) => setNewSubtask(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      addSubtask()
                    }
                  }}
                  sx={{ flex: 1 }}
                />
                <Button onClick={addSubtask} disabled={busy || !newSubtask.trim()}>
                  Ajouter une sous-tâche
                </Button>
              </Stack>
            </Box>
          )}
        </Stack>
  )

  const actions = (
    <>
      <Button onClick={onClose}>Annuler</Button>
      <Button onClick={handleSubmit} variant="contained" disabled={submitting}>
        Enregistrer
      </Button>
    </>
  )

  if (variant === 'panel') {
    if (!open || !task) return null

    return (
      <Paper
        component="aside"
        aria-label="Fiche de la tâche"
        variant="outlined"
        sx={{ width: { xs: '100%', md: 420 }, flexShrink: 0, display: 'flex', flexDirection: 'column', alignSelf: { md: 'flex-start' } }}
      >
        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ px: 2, pt: 1.5 }}>
          <Typography variant="h6" component="h2">
            Modifier la tâche
          </Typography>
          <IconButton size="small" aria-label="Fermer la fiche" onClick={onClose}>
            <CloseIcon fontSize="small" />
          </IconButton>
        </Stack>
        <Box sx={{ px: 2, pb: 1 }}>{fields}</Box>
        <Stack direction="row" justifyContent="flex-end" spacing={1} sx={{ px: 2, py: 1.5 }}>
          {actions}
        </Stack>
      </Paper>
    )
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Modifier la tâche</DialogTitle>
      <DialogContent>{fields}</DialogContent>
      <DialogActions>{actions}</DialogActions>
    </Dialog>
  )
}
