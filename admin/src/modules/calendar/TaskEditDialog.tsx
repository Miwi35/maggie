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
import Link from '@mui/material/Link'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'

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
}

interface TaskEditDialogProps {
  open: boolean
  task: TaskData | null
  onClose: () => void
  onUpdated: () => void
}

/** React-admin hands back resources as IRIs; `parent` wants one either way. */
export const taskIri = (id: string): string => `/api/tasks/${id.split('/').pop() ?? id}`

/** Siblings by `position` as a string, then by id — the API's own order. */
const bySiblingOrder = (a: TaskData, b: TaskData): number => {
  const pa = a.position ?? ''
  const pb = b.position ?? ''
  if (pa !== pb) return pa < pb ? -1 : 1
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

export const TaskEditDialog = ({ open, task, onClose, onUpdated }: TaskEditDialogProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('medium')
  const [criticality, setCriticality] = useState('low')
  const [dueDate, setDueDate] = useState('')
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

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Modifier la tâche</DialogTitle>
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
            label="Notes"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
          />
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
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button onClick={handleSubmit} variant="contained" disabled={submitting}>
          Enregistrer
        </Button>
      </DialogActions>
    </Dialog>
  )
}
