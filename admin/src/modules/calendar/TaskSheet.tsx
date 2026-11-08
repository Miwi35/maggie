import { useEffect, useState, type ReactElement } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import CloseIcon from '@mui/icons-material/Close'
import EditIcon from '@mui/icons-material/Edit'
import { MarkdownText } from '../../components/markdown/MarkdownText'
import { addDays, parseDay } from '../../dates'
import { remindersToFrenchText, remindersFromMinutes } from './ReminderPicker'
import { UrgencyBadge } from './UrgencyBadge'
import { bySiblingOrder, MOMENT_LABELS, taskIri } from './TaskFormDialog'
import type { TaskData } from './TaskFormDialog'

const LEVELS: Record<string, string> = { low: 'Basse', medium: 'Moyenne', high: 'Haute', critical: 'Critique' }

const longDay = (day: string): string =>
  parseDay(day.slice(0, 10)).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'short' })

/** « mardi 3 nov. », then « · 18:00 » or « · Matin » when the task has a moment. */
export const scheduledLabel = (task: TaskData): string | null => {
  if (!task.scheduledDate) return null
  const moment = task.scheduledMoment
  const when =
    moment === 'time' ? task.scheduledTime : moment ? MOMENT_LABELS[moment] : null

  return `${longDay(task.scheduledDate)}${when ? ` · ${when}` : ''}`
}

const Fact = ({ label, children }: { label: string; children: string | null | undefined | ReactElement }) => (
  <Box sx={{ display: 'flex', gap: 2 }}>
    <Typography variant="body2" color="text.secondary" sx={{ width: 96, flexShrink: 0 }}>
      {label}
    </Typography>
    <Typography variant="body2" component="div">
      {children}
    </Typography>
  </Box>
)

interface TaskSheetProps {
  /** The task, its `id` the IRI the data provider wants. */
  task: TaskData
  /** The local day, `YYYY-MM-DD`. */
  today: string
  onClose: () => void
  /** The pencil: opens « Modifier la tâche ». */
  onEdit: () => void
  /** After a change the sheet makes (done, put off, a box, a subtask): the page reloads. */
  onChanged: () => void
}

/**
 * The sheet of a task beside the list (MAG-723): read only. Its fields are written in the form, which the
 * pencil opens; here a task is ticked, put off, its notes' boxes and its subtasks are ticked.
 */
export const TaskSheet = ({ task, today, onClose, onEdit, onChanged }: TaskSheetProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const [subtasks, setSubtasks] = useState<TaskData[]>([])
  const [busy, setBusy] = useState(false)
  const [notes, setNotes] = useState(task.description || '')
  const done = task.completedAt != null
  const fail = (error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' })

  useEffect(() => {
    setNotes(task.description || '')
  }, [task])

  useEffect(() => {
    if (task.parent) return
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
        if (current) fail(error)
      })

    return () => {
      current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [task, dataProvider])

  const patch = (data: Record<string, unknown>, message?: string) =>
    dataProvider
      .update('tasks', { id: task.id, data, previousData: task })
      .then(() => {
        if (message) notify(message, { type: 'success' })
        onChanged()
      })
      .catch(fail)

  // The list is kept locally after each write: the collection is read from Elasticsearch, which lags.
  const toggleSubtask = (subtask: TaskData, checked: boolean) => {
    const completedAt = checked ? new Date().toISOString() : null
    const set = (value: string | null) =>
      setSubtasks((list) => list.map((s) => (s.id === subtask.id ? { ...s, completedAt: value } : s)))
    set(completedAt)
    dataProvider.update('tasks', { id: subtask.id, data: { completedAt }, previousData: subtask }).catch((error: Error) => {
      set(subtask.completedAt ?? null)
      fail(error)
    })
  }

  // One PATCH on the moved subtask with its neighbour's position: the API swaps the two.
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
    setNotes(next)
    patch({ description: next.trim() || null })
  }

  const scheduled = scheduledLabel(task)
  const reminders = remindersToFrenchText(remindersFromMinutes(task.reminders ?? []))
  const links = task.links ?? []

  return (
    <Paper
      component="aside"
      aria-label="Fiche de la tâche"
      variant="outlined"
      sx={{ width: { xs: '100%', md: 420 }, flexShrink: 0, display: 'flex', flexDirection: 'column', alignSelf: { md: 'flex-start' } }}
    >
      <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ px: 2, pt: 1.5 }}>
        <Typography variant="h6" component="h2" sx={{ textDecoration: done ? 'line-through' : 'none' }}>
          {task.title}
        </Typography>
        <Box sx={{ display: 'flex', flexShrink: 0 }}>
          <IconButton size="small" aria-label="Modifier la tâche" onClick={onEdit}>
            <EditIcon fontSize="small" />
          </IconButton>
          <IconButton size="small" aria-label="Fermer la fiche" onClick={onClose}>
            <CloseIcon fontSize="small" />
          </IconButton>
        </Box>
      </Stack>

      <Stack spacing={1} sx={{ px: 2, py: 1.5 }}>
        {task.dueDate && <Fact label="Échéance">{longDay(task.dueDate)}</Fact>}
        {scheduled && <Fact label="Programmée">{scheduled}</Fact>}
        {reminders && <Fact label="Rappels">{reminders}</Fact>}
        <Fact label="Criticité">{LEVELS[task.criticality] ?? task.criticality}</Fact>
        <Fact label="Priorité">{LEVELS[task.priority] ?? task.priority}</Fact>
        {task.urgency && (
          <Fact label="Urgence">
            <UrgencyBadge urgency={task.urgency} />
          </Fact>
        )}
        {task.estimatedMinutes != null && <Fact label="Durée">{`${task.estimatedMinutes} min`}</Fact>}
        {task.firstStep && <Fact label="Premier pas">{task.firstStep}</Fact>}

        {notes.trim() && (
          <Box component="section" aria-label="Notes">
            <Typography variant="subtitle2">Notes</Typography>
            <MarkdownText value={notes} onChange={tickBox} />
          </Box>
        )}

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

        {subtasks.length > 0 && (
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
                    control={<Checkbox checked={s.completedAt != null} onChange={(e) => toggleSubtask(s, e.target.checked)} />}
                    label={s.title}
                    sx={{ textDecoration: s.completedAt != null ? 'line-through' : 'none', pr: 9 }}
                  />
                </ListItem>
              ))}
            </List>
          </Box>
        )}
      </Stack>

      <Stack direction="row" justifyContent="flex-end" spacing={1} sx={{ px: 2, py: 1.5 }}>
        {!done && (
          <Button onClick={() => patch({ dueDate: addDays(today, 1) }, 'Tâche reportée à demain')}>Reporter</Button>
        )}
        <Button
          variant="contained"
          onClick={() =>
            patch({ completedAt: done ? null : new Date().toISOString() }, done ? 'Tâche rouverte' : 'Tâche terminée')
          }
        >
          {done ? 'Rouvrir' : 'Marquer comme faite'}
        </Button>
      </Stack>
    </Paper>
  )
}
