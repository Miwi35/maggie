import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useNotify } from 'react-admin'
import { useSearchParams } from 'react-router-dom'
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
import UpdateIcon from '@mui/icons-material/Update'
import { addDays, localDay, parseDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { TaskBravoBanner, TaskPalier } from './TaskBravo'
import { scrollToRow, TaskDay } from './TaskDay'
import { TaskEditDialog } from './TaskEditDialog'
import { TaskEvening } from './TaskEvening'
import { TaskMorning } from './TaskMorning'
import { TaskRoutines } from './TaskRoutines'
import { UrgencyBadge } from './UrgencyBadge'
import { claimEvening, claimMorning, eveningDue, fetchDayPlan, fetchDayTimes, morningDue } from './dayPlan'
import type { DayPlan } from './dayPlan'
import { fetchDayReview } from './dayReview'
import { DEFAULT_CHEER, fetchReward, fetchRewardCheer, parseReward, playBravoSound } from './reward'
import type { Reward, RewardCheer } from './reward'
import { createTask, fetchTask, fetchTaskView, findTaskView, postponedLabel, setTaskDone, TASK_VIEWS } from './taskViews'
import type { TaskCounts, TaskView, ViewTask } from './taskViews'

// The day is published apart from its tasks (MAG-497): a tick moves both. So is the evening's review (MAG-500).
// The rewards come on the same stream: a palier opens its bravo wherever the page is (MAG-509).
const TASK_TOPICS = ['/api/tasks/{id}', '/api/day_plans/{id}', '/api/day_reviews/{id}', '/api/rewards/{id}']
const PAGE_SIZE = 30

/** The four views of the tasks, and the routines (MAG-511), which are not tasks. */
type BoardTab = TaskView | 'routines'

const TABS: Record<TaskView, { label: string; empty: string }> = {
  today: { label: "Aujourd'hui", empty: "Rien à faire aujourd'hui" },
  upcoming: { label: 'À venir', empty: 'Aucune tâche à venir' },
  someday: { label: 'À faire un jour', empty: 'Aucune tâche pour plus tard' },
  done: { label: 'Faites', empty: 'Aucune tâche terminée' },
}

/** The due day a quick-created task gets: tomorrow in « À venir », none in « À faire un jour ». */
const defaultDueDate = (view: BoardTab): string | undefined => {
  if (view === 'upcoming') return addDays(localDay(new Date()), 1)

  return undefined
}

/** The tab a link opens: `#/tasks?tab=routines` (the home widget). Read once, the page keeps its own tabs after. */
const initialTab = (): BoardTab =>
  new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('tab') === 'routines' ? 'routines' : 'today'

const shortDay = (day: string): string =>
  parseDay(day).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })

/**
 * The « Tâches » page (MAG-408): « Aujourd'hui » — the day of five tasks at most
 * and its reserve (MAG-497) — then three views the API sorts: « À venir »,
 * « À faire un jour » and « Faites ». The API decides which view a task is in
 * (it knows the user's day), so the page only draws, ticks, creates and refetches.
 */
export const TaskBoard = () => {
  const notify = useNotify()
  const [searchParams] = useSearchParams()
  // `#/tasks?task=<id>`: the task a notification or a search hit points at (MAG-640).
  const target = searchParams.get('task')?.split('/').pop() || null
  const [highlighted, setHighlighted] = useState<string | null>(null)

  const [view, setView] = useState<BoardTab>(initialTab)
  const [page, setPage] = useState(1)
  const [tasks, setTasks] = useState<ViewTask[]>([])
  const [total, setTotal] = useState(0)
  const [counts, setCounts] = useState<TaskCounts | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [title, setTitle] = useState('')
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<ViewTask | null>(null)
  const [day, setDay] = useState<DayPlan | null>(null)
  const [dayFailed, setDayFailed] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const [reviewing, setReviewing] = useState<DayPlan | null>(null)
  const [opening, setOpening] = useState(true)
  const [cheer, setCheer] = useState<RewardCheer>(DEFAULT_CHEER)
  const [palier, setPalier] = useState<Reward | null>(null)
  const [banner, setBanner] = useState<{ title: string; reward: Reward | null }>({ title: '', reward: null })

  const today = localDay(new Date())

  // Only the answer to the last request is drawn: a slow one must not overwrite a newer tab.
  const request = useRef(0)
  const dayRequest = useRef(0)

  // The day is read whatever the tab: the « Aujourd'hui » counter is its tasks left to do.
  const loadDay = useCallback(() => {
    const current = ++dayRequest.current
    fetchDayPlan(today)
      .then((result) => {
        if (current !== dayRequest.current) return
        setDay(result)
        setDayFailed(false)
      })
      .catch((error: unknown) => {
        if (current !== dayRequest.current) return
        console.error(error)
        setDayFailed(true)
      })
  }, [today])

  // A write answered with the day wins over a read sent before it.
  const showDay = useCallback((result: DayPlan) => {
    dayRequest.current++
    setDay(result)
    setDayFailed(false)
  }, [])

  const load = useCallback(() => {
    if (view === 'routines') return
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
  // On « Aujourd'hui » the view is still read: its answer carries the other tabs' counts.
  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
    load()
  }, [load])

  const loadDayRef = useRef(loadDay)
  useEffect(() => {
    loadDayRef.current = loadDay
    loadDay()
  }, [loadDay])

  // At the first visit of the day, the evening opens after the evening time on a day that holds tasks
  // until « Bonne nuit » (MAG-500); else the morning, after the morning time while the day is not
  // confirmed (MAG-498). Each opens once per load. The day waits for that answer, so it does not
  // flash before the morning or the evening replaces it.
  const decided = useRef(false)
  useEffect(() => {
    if (!day || decided.current) return
    decided.current = true
    const opened = day
    const now = new Date()
    fetchDayTimes()
      .then(async ({ morningTime, eveningTime }) => {
        if (eveningDue(now, eveningTime, opened.total, null)) {
          const review = await fetchDayReview(opened.date).catch(() => null)
          if (review && eveningDue(now, eveningTime, opened.total, review.reviewedAt) && claimEvening()) {
            setReviewing(opened)
            return
          }
        }
        if (morningDue(now, morningTime, opened.confirmedAt) && claimMorning()) setPreparing(true)
      })
      .catch(() => undefined)
      .finally(() => setOpening(false))
  }, [day])

  // The tab that holds the linked task opens, the task is marked and scrolled to; an unknown one leaves the board as it is.
  const located = useRef<string | null>(null)
  useEffect(() => {
    if (!target) {
      located.current = null
      setHighlighted(null)
      return
    }
    if (!day || located.current === target) return
    located.current = target
    if (day.slots.some((slot) => slot.task.id === target) || day.reserve.some((task) => task.id === target)) {
      setView('today')
      setHighlighted(target)
      return
    }
    findTaskView(target)
      .then((found) => {
        if (located.current !== target || !found) return
        setLoading(true)
        setFailed(false)
        setTasks([])
        setPage(found.page)
        setView(found.view)
        setHighlighted(target)
      })
      .catch(console.error)
  }, [target, day])

  const reload = useCallback(() => {
    loadRef.current()
    loadDayRef.current()
  }, [])

  const confirmMorning = useCallback(
    (result: DayPlan) => {
      showDay(result)
      setPreparing(false)
      reload()
    },
    [showDay, reload],
  )

  const closeEvening = useCallback(() => {
    setReviewing(null)
    reload()
  }, [reload])

  // The day only carries what it draws: the form wants the whole task.
  const edit = (taskId: string) => {
    fetchTask(taskId)
      .then(setEditing)
      .catch((error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' }))
  }

  // A palier is the one reward that comes unasked, from a chapter finished elsewhere.
  useMercure(TASK_TOPICS, (data) => {
    reload()
    const reward = parseReward(data)
    if (reward?.level === 'palier') {
      if (cheer.sound) playBravoSound()
      setPalier(reward)
    }
  })

  // How a task done is cheered (MAG-508), as Réglages › Tâches says when the page opens.
  useEffect(() => {
    fetchRewardCheer().then(setCheer)
  }, [])

  const selectView = (_: unknown, next: BoardTab) => {
    if (next === view) return
    setHighlighted(null)
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
        reload()
        if (!done) return notify('Tâche rouverte', { type: 'success' })
        // Away from the day, the bravo is a short banner (MAG-508).
        return fetchReward(task['@id'] ?? task.id)
          .then((reward) => {
            if (!reward) throw new Error('no reward')
            if (cheer.sound) playBravoSound()
            setBanner({ title: task.title, reward })
          })
          .catch(() => notify('Tâche terminée', { type: 'success' }))
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

  const tabLabel = (name: TaskView): string => {
    if (name === 'today') return day ? `${TABS.today.label} (${day.total - day.done})` : TABS.today.label

    return counts ? `${TABS[name].label} (${counts[name]})` : TABS[name].label
  }

  const listed = new Set(tasks.flatMap((t) => [t['@id'], t.id]))
  const pages = Math.ceil(total / PAGE_SIZE)

  return (
    <Box sx={{ p: 3, maxWidth: 900, mx: 'auto' }}>
      <Typography variant="h5" sx={{ mb: 2 }}>
        Tâches
      </Typography>

      <Tabs value={view} onChange={selectView} variant="scrollable" aria-label="Vues des tâches" sx={{ mb: 2 }}>
        {/* « Routines » right after « Aujourd'hui », as on the phone (MAG-511). */}
        {TASK_VIEWS.flatMap((name) => {
          const tab = <Tab key={name} value={name} label={tabLabel(name)} />

          return name === 'today' ? [tab, <Tab key="routines" value="routines" label="Routines" />] : [tab]
        })}
      </Tabs>

      {view !== 'done' && view !== 'today' && view !== 'routines' && (
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

      {view === 'routines' ? (
        <TaskRoutines animate={cheer.animation} />
      ) : view === 'today' && reviewing ? (
        <TaskEvening day={reviewing} onClose={closeEvening} onDone={closeEvening} animate={cheer.animation} />
      ) : view === 'today' && preparing ? (
        <TaskMorning date={today} onClose={() => setPreparing(false)} onConfirmed={confirmMorning} />
      ) : view === 'today' ? (
        dayFailed ? (
          <Alert severity="error">Tâches indisponibles</Alert>
        ) : !day || opening ? (
          [1, 2, 3].map((i) => <Skeleton key={i} height={40} />)
        ) : (
          <TaskDay
            day={day}
            today={today}
            onDay={showDay}
            onReload={reload}
            onEdit={edit}
            highlightId={highlighted}
            onChange={() => setPreparing(true)}
            onEvening={() => setReviewing(day)}
            cheer={cheer}
          />
        )
      ) : failed ? (
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
            // Put off, never late: a critical task keeps its own mark instead (MAG-497).
            const postponed = task.urgency === 'critical' ? null : postponedLabel(task.postponedSince, today)
            // A subtask goes under its task, as it comes in the list.
            const indented = !!task.parent && listed.has(task.parent)

            return (
              <ListItem
                key={task.id}
                ref={task.id === highlighted ? scrollToRow : undefined}
                aria-current={task.id === highlighted ? 'true' : undefined}
                disablePadding
                sx={{ pl: indented ? 4 : 0, borderRadius: 1, ...(task.id === highlighted && { bgcolor: 'action.selected' }) }}
              >
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
                  {postponed && (
                    <Typography
                      component="span"
                      variant="caption"
                      color="text.secondary"
                      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
                    >
                      <UpdateIcon aria-hidden fontSize="inherit" />
                      {postponed}
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

      {palier && <TaskPalier reward={palier} animate={cheer.animation} onClose={() => setPalier(null)} />}

      <TaskBravoBanner title={banner.title} reward={banner.reward} onClose={() => setBanner((b) => ({ ...b, reward: null }))} />

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
