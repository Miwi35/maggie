import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNotify } from 'react-admin'
import { Link as RouterLink, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Pagination from '@mui/material/Pagination'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { addDays, localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { TaskBravoBanner, TaskPalier } from './TaskBravo'
import { TaskDay } from './TaskDay'
import { TaskFormDialog } from './TaskFormDialog'
import { TaskSheet } from './TaskSheet'
import { TaskList } from './TaskList'
import type { ListedTask } from './TaskList'
import { TaskMorning } from './TaskMorning'
import { TaskRoutines } from './TaskRoutines'
import { claimMorning, dayTasks, fetchDayPlan, fetchDayTimes, morningDue } from './dayPlan'
import type { DayPlan } from './dayPlan'
import { DEFAULT_CHEER, fetchReward, fetchRewardCheer, parseReward, playBravoSound } from './reward'
import type { Reward, RewardCheer } from './reward'
import { fetchTask, fetchTaskView, findTaskView, setTaskDone, TASK_TOPICS } from './taskViews'
import type { TaskView, ViewTask } from './taskViews'

const PAGE_SIZE = 30

/** The views of the tasks, each a route (MAG-644), and the routines (MAG-511), which are not tasks. */
type BoardView = TaskView | 'routines'

const VIEW_PATHS: Record<BoardView, string> = {
  today: '/tasks',
  upcoming: '/tasks/upcoming',
  someday: '/tasks/someday',
  done: '/tasks/done',
  routines: '/tasks/routines',
}

const EMPTY: Record<TaskView, string> = {
  today: "Rien à faire aujourd'hui",
  upcoming: 'Aucune tâche à venir',
  someday: 'Aucune tâche pour plus tard',
  done: 'Aucune tâche terminée',
}

const LABELS: Record<TaskView, string> = {
  today: "Aujourd'hui",
  upcoming: 'À venir',
  someday: 'Un jour',
  done: 'Faites',
}

const viewOfPath = (pathname: string): BoardView => {
  const path = pathname.replace(/\/+$/, '') || '/'

  return (Object.keys(VIEW_PATHS) as BoardView[]).find((view) => VIEW_PATHS[view] === path) ?? 'today'
}

const TITLE_DATE = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })

/** The data provider wants the IRI as id. */
const withIri = (task: ViewTask | null): ViewTask | null => (task ? { ...task, id: task['@id'] ?? task.id } : null)

interface TasksPageProps {
  view: BoardView
}

/**
 * One view of the « Tâches » module (MAG-408, MAG-644), at full width: the list under its header, the
 * sheet of a task beside it. The API decides which view a task is in (it knows the user's day), so the
 * page only draws, ticks, creates and refetches.
 */
const TasksPage = ({ view }: TasksPageProps) => {
  const notify = useNotify()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const setParams = useRef(setSearchParams)
  useEffect(() => {
    setParams.current = setSearchParams
  })
  // `#/tasks?task=<id>`: the task a notification or a search hit points at (MAG-640).
  const target = searchParams.get('task')?.split('/').pop() || null
  const page = view === 'done' ? Math.max(1, Number(searchParams.get('page')) || 1) : 1

  const [tasks, setTasks] = useState<ViewTask[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [form, setForm] = useState<{ task: ViewTask | null } | null>(null)
  const [editing, setEditing] = useState<ViewTask | null>(null)
  const [day, setDay] = useState<DayPlan | null>(null)
  const [dayFailed, setDayFailed] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const [opening, setOpening] = useState(true)
  const [cheer, setCheer] = useState<RewardCheer>(DEFAULT_CHEER)
  const [palier, setPalier] = useState<Reward | null>(null)
  const [banner, setBanner] = useState<{ title: string; reward: Reward | null }>({ title: '', reward: null })

  const today = localDay(new Date())

  // Only the answer to the last request is drawn: a slow one must not overwrite a newer page.
  const request = useRef(0)
  const dayRequest = useRef(0)

  const loadDay = useCallback(() => {
    if (view !== 'today') return
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
  }, [view, today])

  // A write answered with the day wins over a read sent before it.
  const showDay = useCallback((result: DayPlan) => {
    dayRequest.current++
    setDay(result)
    setDayFailed(false)
  }, [])

  const load = useCallback(() => {
    if (view === 'routines' || view === 'today') return
    const current = ++request.current
    fetchTaskView(view, page)
      .then((result) => {
        if (current !== request.current) return
        const lastPage = Math.max(1, Math.ceil(result.totalItems / PAGE_SIZE))
        // The last page was emptied (a task unticked): go back to the last one that holds tasks.
        if (page > lastPage) {
          setParams.current({ page: String(lastPage) }, { replace: true })
          return
        }
        setTasks(result.member)
        setTotal(result.totalItems)
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

  // Callbacks that outlive a render (a tick answered after a navigation) must reload what is shown now.
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

  // At the first visit of the day, the morning opens after the morning time while the day is not
  // confirmed (MAG-498), once per load. The day waits for that answer, so it does not flash before
  // the morning replaces it. There is no evening here: the day stays open at any hour (MAG-825).
  const decided = useRef(false)
  const linked = useRef(target)
  useEffect(() => {
    linked.current = target
  })
  useEffect(() => {
    if (!day || decided.current) return
    decided.current = true
    const opened = day
    const at = new Date()
    fetchDayTimes()
      .then(({ morningTime }) => {
        // A link to a task shows that task: the morning does not cover it.
        if (linked.current) return
        if (morningDue(at, morningTime, opened.confirmedAt) && claimMorning()) setPreparing(true)
      })
      .catch(() => undefined)
      .finally(() => setOpening(false))
  }, [day])

  // The task a link points at is marked and scrolled to when this view lists it; else the view that
  // holds it opens. An unknown task leaves the page as it is.
  const listedIds = useMemo(
    () => new Set(view === 'today' ? (day ? dayTasks(day, today) : []).map((task) => task.id) : tasks.map((task) => task.id)),
    [view, day, tasks, today],
  )
  const highlighted = target && listedIds.has(target) ? target : null
  const ready = view === 'today' ? day !== null : view !== 'routines' && !loading && !failed
  const located = useRef<string | null>(null)
  useEffect(() => {
    if (!target) {
      located.current = null
      return
    }
    if (!ready || located.current === target) return
    located.current = target
    if (listedIds.has(target)) return
    findTaskView(target)
      .then((found) => {
        if (located.current !== target) return
        if (found) {
          if (found.view === view && found.page === page) return
          const query = new URLSearchParams({ task: target, ...(found.page > 1 ? { page: String(found.page) } : {}) })
          navigate({ pathname: VIEW_PATHS[found.view], search: `?${query.toString()}` }, { replace: true })
        } else if (view !== 'today') {
          navigate({ pathname: VIEW_PATHS.today, search: `?task=${target}` }, { replace: true })
        }
      })
      .catch(console.error)
  }, [target, ready, listedIds, view, page, navigate])

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

  // The day only carries what it draws: the sheet wants the whole task.
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

  const toggle = (task: ListedTask) => {
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

  // Memoised: a new object each render would reset the forms.
  const sheetTask = useMemo(() => withIri(editing), [editing])
  const formTask = useMemo(() => withIri(form?.task ?? null), [form])

  // After a write: the list is reloaded and the sheet, if open, shows the task as it now is (gone: closed).
  const refreshSheet = () => {
    reload()
    if (!editing) return
    fetchTask(editing.id)
      .then(setEditing)
      .catch(() => setEditing(null))
  }

  // « Aujourd'hui » lists only what is due today or before, so a task made there is due today, and tomorrow on
  // « À venir »: it would vanish from the view it was made in.
  const defaultDueDate = view === 'today' ? today : view === 'upcoming' ? addDays(today, 1) : undefined

  const pages = Math.ceil(total / PAGE_SIZE)
  const canAdd = view === 'today' || view === 'upcoming' || view === 'someday'

  let content
  if (view === 'routines') {
    content = <TaskRoutines animate={cheer.animation} />
  } else if (view === 'today') {
    content =
      preparing ? (
        <TaskMorning date={today} onClose={() => setPreparing(false)} onConfirmed={confirmMorning} />
      ) : dayFailed ? (
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
          selectedId={editing?.id ?? null}
          onChange={() => setPreparing(true)}
          cheer={cheer}
        />
      )
  } else if (failed) {
    content = <Alert severity="error">Tâches indisponibles</Alert>
  } else if (loading) {
    content = [1, 2, 3].map((i) => <Skeleton key={i} height={40} />)
  } else if (tasks.length === 0) {
    content = (
      <Typography variant="body2" color="text.secondary">
        {EMPTY[view]}
      </Typography>
    )
  } else {
    content = (
      <TaskList
        groups={[{ tasks }]}
        today={today}
        label={LABELS[view]}
        highlightId={highlighted}
        selectedId={editing?.id ?? null}
        onToggle={toggle}
        onOpen={(task) => setEditing(tasks.find((candidate) => candidate.id === task.id) ?? null)}
      />
    )
  }

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" alignItems="center" flexWrap="wrap" gap={2} sx={{ mb: 2 }}>
        <Typography component="p" variant="subtitle1" color="text.secondary" sx={{ flex: 1, minWidth: 200 }}>
          Tâches · {TITLE_DATE.format(new Date())}
        </Typography>
        {canAdd && (
          <Button variant="contained" onClick={() => setForm({ task: null })}>
            + Nouvelle tâche
          </Button>
        )}
        {view !== 'routines' && (
          <Button component={RouterLink} to={VIEW_PATHS.routines} color="inherit">
            Routines
          </Button>
        )}
      </Stack>

      <Box sx={{ display: 'flex', flexDirection: { xs: 'column', md: 'row' }, alignItems: { md: 'flex-start' }, gap: 3 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          {content}

          {view === 'done' && !failed && pages > 1 && (
            <Pagination
              count={pages}
              page={page}
              onChange={(_, next) => {
                setLoading(true)
                setSearchParams(next > 1 ? { page: String(next) } : {})
              }}
              sx={{ mt: 2 }}
            />
          )}
        </Box>

        {sheetTask && view !== 'routines' && (
          <TaskSheet
            task={sheetTask}
            today={today}
            onClose={() => setEditing(null)}
            onEdit={() => setForm({ task: editing })}
            onChanged={refreshSheet}
          />
        )}
      </Box>

      <TaskFormDialog
        open={form !== null}
        task={formTask}
        defaultDueDate={defaultDueDate}
        onClose={() => setForm(null)}
        onSaved={refreshSheet}
      />

      {palier && <TaskPalier reward={palier} animate={cheer.animation} onClose={() => setPalier(null)} />}

      <TaskBravoBanner title={banner.title} reward={banner.reward} onClose={() => setBanner((b) => ({ ...b, reward: null }))} />
    </Box>
  )
}

/** The module's page: the view is the route (`/tasks`, `/tasks/upcoming`…), and a view starts from nothing. */
export const TaskBoard = () => {
  const { pathname } = useLocation()
  const view = viewOfPath(pathname)

  return <TasksPage key={view} view={view} />
}
