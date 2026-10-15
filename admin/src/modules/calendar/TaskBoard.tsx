import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useNotify } from 'react-admin'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import Pagination from '@mui/material/Pagination'
import Skeleton from '@mui/material/Skeleton'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import EventBusyIcon from '@mui/icons-material/EventBusy'
import { addDays, localDay, parseDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { TaskEditDialog } from './TaskEditDialog'
import { UrgencyBadge } from './UrgencyBadge'
import { createTask, fetchTaskView, setTaskDone, TASK_VIEWS } from './taskViews'
import type { TaskCounts, TaskView, ViewTask } from './taskViews'

const TASK_TOPICS = ['/api/tasks/{id}']
const PAGE_SIZE = 30

const TABS: Record<TaskView, { label: string; empty: string }> = {
  today: { label: "Aujourd'hui", empty: "Rien à faire aujourd'hui" },
  upcoming: { label: 'À venir', empty: 'Aucune tâche à venir' },
  someday: { label: 'À faire un jour', empty: 'Aucune tâche pour plus tard' },
  done: { label: 'Faites', empty: 'Aucune tâche terminée' },
}

/** The due day a quick-created task gets in each tab: none in « À faire un jour » and « Faites ». */
const defaultDueDate = (view: TaskView): string | undefined => {
  if (view === 'today') return localDay(new Date())
  if (view === 'upcoming') return addDays(localDay(new Date()), 1)

  return undefined
}

const shortDay = (day: string): string =>
  parseDay(day).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })

/**
 * The « Tâches » page (MAG-408): four views the API sorts — « Aujourd'hui » (the
 * late first), « À venir », « À faire un jour » and « Faites » — each task in
 * exactly one. The API decides which view a task is in (it knows the user's
 * day), so the page only draws, ticks, creates and refetches.
 */
export const TaskBoard = () => {
  const notify = useNotify()

  const [view, setView] = useState<TaskView>('today')
  const [page, setPage] = useState(1)
  const [tasks, setTasks] = useState<ViewTask[]>([])
  const [total, setTotal] = useState(0)
  const [counts, setCounts] = useState<TaskCounts | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [title, setTitle] = useState('')
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<ViewTask | null>(null)

  // Only the answer to the last request is drawn: a slow one must not overwrite a newer tab.
  const request = useRef(0)

  const load = useCallback(() => {
    const current = ++request.current
    fetchTaskView(view, page)
      .then((result) => {
        if (current !== request.current) return
        const lastPage = Math.max(1, Math.ceil(result.totalItems / PAGE_SIZE))
        // The last page was emptied (a task unticked): go back to the last one that holds tasks.
        if (page > lastPage) {
          setPage(lastPage)
          return
        }
        setTasks(result.member)
        setTotal(result.totalItems)
        setCounts(result.counts)
        setFailed(false)
      })
      .catch((error: unknown) => {
        if (current !== request.current) return
        console.error(error)
        setFailed(true)
      })
      .finally(() => {
        if (current === request.current) setLoading(false)
      })
  }, [view, page])

  // Callbacks that outlive a render (a tick answered after a tab switch) must reload the tab shown now.
  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
    load()
  }, [load])

  const reload = useCallback(() => loadRef.current(), [])

  useMercure(TASK_TOPICS, reload)

  const selectView = (_: unknown, next: TaskView) => {
    if (next === view) return
    setLoading(true)
    setFailed(false)
    setTasks([])
    setPage(1)
    setView(next)
  }

  const toggle = (task: ViewTask) => {
    const done = task.completedAt == null
    setTaskDone(task['@id'] ?? task.id, done)
      .then(() => {
        notify(done ? 'Tâche terminée' : 'Tâche rouverte', { type: 'success' })
        reload()
      })
      .catch((error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' }))
  }

  const create = () => {
    const value = title.trim()
    if (!value || creating) return
    setCreating(true)
    createTask(value, defaultDueDate(view))
      .then(() => {
        setTitle('')
        reload()
      })
      .catch((error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' }))
      .finally(() => setCreating(false))
  }

  const onTitleKey = (event: KeyboardEvent) => {
    if (event.key !== 'Enter') return
    event.preventDefault()
    create()
  }

  const listed = new Set(tasks.flatMap((t) => [t['@id'], t.id]))
  const pages = Math.ceil(total / PAGE_SIZE)

  return (
    <Box sx={{ p: 3, maxWidth: 900, mx: 'auto' }}>
      <Typography variant="h5" sx={{ mb: 2 }}>
        Tâches
      </Typography>

      <Tabs value={view} onChange={selectView} variant="scrollable" aria-label="Vues des tâches" sx={{ mb: 2 }}>
        {TASK_VIEWS.map((name) => (
          <Tab
            key={name}
            value={name}
            label={counts ? `${TABS[name].label} (${counts[name]})` : TABS[name].label}
          />
        ))}
      </Tabs>

      {view !== 'done' && (
        <TextField
          label="Nouvelle tâche"
          size="small"
          fullWidth
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          onKeyDown={onTitleKey}
          disabled={creating}
          sx={{ mb: 2 }}
        />
      )}

      {failed ? (
        <Alert severity="error">Tâches indisponibles</Alert>
      ) : loading ? (
        [1, 2, 3].map((i) => <Skeleton key={i} height={40} />)
      ) : tasks.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {TABS[view].empty}
        </Typography>
      ) : (
        <List disablePadding aria-label={TABS[view].label}>
          {tasks.map((task) => {
            const done = task.completedAt != null
            const late = !done && task.overdueDays != null && task.overdueDays > 0
            // A subtask goes under its task, as it comes in the list.
            const indented = !!task.parent && listed.has(task.parent)

            return (
              <ListItem key={task.id} disablePadding sx={{ pl: indented ? 4 : 0 }}>
                <Checkbox
                  size="small"
                  checked={done}
                  onChange={() => toggle(task)}
                  slotProps={{ input: { 'aria-label': `${done ? 'Rouvrir' : 'Terminer'} ${task.title}` } }}
                />
                <ListItemButton
                  dense
                  onClick={() => setEditing(task)}
                  sx={{ borderRadius: 1, gap: 1, flexWrap: 'wrap' }}
                >
                  <Typography
                    variant="body2"
                    sx={{
                      flex: 1,
                      minWidth: 0,
                      textDecoration: done ? 'line-through' : 'none',
                      color: done ? 'text.disabled' : 'text.primary',
                    }}
                  >
                    {task.title}
                  </Typography>
                  <UrgencyBadge urgency={task.urgency} />
                  {late && (
                    <Typography
                      component="span"
                      variant="caption"
                      color="error"
                      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
                    >
                      <EventBusyIcon aria-hidden fontSize="inherit" />
                      En retard de {task.overdueDays} j
                    </Typography>
                  )}
                  {task.dueDate && (
                    <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
                      {late ? `prévue le ${shortDay(task.dueDate)}` : shortDay(task.dueDate)}
                    </Typography>
                  )}
                </ListItemButton>
              </ListItem>
            )
          })}
        </List>
      )}

      {view === 'done' && !failed && pages > 1 && (
        <Pagination
          count={pages}
          page={page}
          onChange={(_, next) => {
            setLoading(true)
            setPage(next)
          }}
          sx={{ mt: 2 }}
        />
      )}

      <TaskEditDialog
        open={editing !== null}
        // The data provider wants the IRI as id.
        task={editing ? { ...editing, id: editing['@id'] ?? editing.id } : null}
        onClose={() => setEditing(null)}
        onUpdated={reload}
      />
    </Box>
  )
}
