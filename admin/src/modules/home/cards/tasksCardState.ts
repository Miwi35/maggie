import type { DayMoment, RoutinesDay } from '../../calendar/routinesApi'
import type { ViewTask } from '../../calendar/taskViews'

/** What the Tâches card draws, composed from the business resources (MAG-588). */
export interface HomeTask {
  id: string
  title: string
  done: boolean
  /** Whole days late; null when the task is not late. */
  overdueDays: number | null
  /** Set on a subtask: it is drawn indented. */
  parentId: string | null
}

export interface HomeRoutine {
  id: string
  title: string
  anchor: string
  /** The hour it is due, `HH:MM`. */
  time: string
  checked: boolean
}

export interface HomeSaga {
  title: string
  done: number
  total: number
}

export interface TasksHomeState {
  module: 'tasks'
  day: 'today' | 'tomorrow'
  /** False for tomorrow: nothing can be ticked. */
  editable: boolean
  tasks: HomeTask[]
  /** The ring: `done` out of `total`. */
  done: number
  total: number
  /** Open tasks the card does not list. */
  more: number
  /** Late open tasks. */
  overdue: number
  routines: HomeRoutine[]
  saga: HomeSaga | null
}

/** The state with a task ticked (or reopened) — what the card shows until the API's own answer comes. */
export function withTaskDone(state: TasksHomeState, id: string, done: boolean): TasksHomeState {
  const task = state.tasks.find((t) => t.id === id)
  if (!task || task.done === done) return state

  const late = task.overdueDays != null && task.overdueDays > 0
  const delta = done ? 1 : -1

  return {
    ...state,
    tasks: state.tasks.map((t) => (t.id === id ? { ...t, done } : t)),
    done: Math.max(0, state.done + delta),
    overdue: late ? Math.max(0, state.overdue - delta) : state.overdue,
  }
}

export function withRoutineChecked(state: TasksHomeState, id: string, checked: boolean): TasksHomeState {
  return { ...state, routines: state.routines.map((r) => (r.id === id ? { ...r, checked } : r)) }
}

/** The card lists this many task lines, ticked ones first claimed. */
const SLOTS = 5

/** What the business resources answered for one day. */
export interface TasksReads {
  /** Today: the « Aujourd'hui » view, in its order. Tomorrow: the tasks due that day, unsorted. */
  open: ViewTask[]
  /** Ticked today, latest first; empty for tomorrow. */
  done: ViewTask[]
  routines: RoutinesDay
}

const idOf = (iri: string | null | undefined): string | null => (iri ? (iri.split('/').pop() ?? null) : null)

const row = (task: ViewTask, done: boolean): HomeTask => ({
  id: task.id,
  title: task.title,
  done,
  overdueDays: done ? null : (task.overdueDays ?? null),
  parentId: idOf(task.parent),
})

const compare = <T extends string | number>(a: T, b: T): number => (a < b ? -1 : a > b ? 1 : 0)

/** Most urgent first, the undated last, then the order among siblings. */
function byUrgency(a: ViewTask, b: ViewTask): number {
  return (
    compare(b.orderScore ?? 0, a.orderScore ?? 0) ||
    (a.dueDate && b.dueDate ? compare(a.dueDate, b.dueDate) : a.dueDate ? -1 : b.dueDate ? 1 : 0) ||
    compare(a.position ?? '', b.position ?? '') ||
    compare(a.id, b.id)
  )
}

/** The routines of the current moment — the last whose hour has passed, the first before the day starts. */
function currentMoment(moments: DayMoment[], now: string): DayMoment[] {
  const passed = moments.filter((moment) => moment.time <= now).length
  const current = moments[Math.max(passed - 1, 0)]

  return current ? [current] : []
}

/** The state of the card for `day` from the reads, `now` being `HH:MM` here. */
export function composeTasksState(day: 'today' | 'tomorrow', reads: TasksReads, now: string): TasksHomeState {
  const today = day === 'today'
  const open = today ? reads.open : [...reads.open].sort(byUrgency)
  const doneRows = today ? reads.done.slice(0, SLOTS) : []
  const openRows = open.slice(0, SLOTS - doneRows.length)
  const moments = today ? currentMoment(reads.routines.moments, now) : reads.routines.moments

  return {
    module: 'tasks',
    day,
    editable: today,
    tasks: [...openRows.map((t) => row(t, false)), ...doneRows.map((t) => row(t, true))],
    done: doneRows.length,
    total: doneRows.length + openRows.length,
    more: open.length - openRows.length,
    overdue: open.filter((t) => (t.overdueDays ?? 0) > 0).length,
    routines: moments.flatMap((moment) =>
      moment.routines.map((r) => ({ id: r.id, title: r.title, anchor: r.anchor, time: r.time, checked: r.checked })),
    ),
    saga: null,
  }
}

/** Local midnight of `day` (`YYYY-MM-DD`) as an ISO instant carrying the local offset. */
export function localMidnightIso(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  const at = new Date(y, m - 1, d)
  const two = (n: number) => String(Math.abs(n)).padStart(2, '0')
  const offset = -at.getTimezoneOffset()

  return `${day}T${two(at.getHours())}:${two(at.getMinutes())}:00${offset < 0 ? '-' : '+'}${two(Math.trunc(offset / 60))}:${two(offset % 60)}`
}

/** Nothing to show — the card of a module that is absent. */
export const emptyTasksState = (day: 'today' | 'tomorrow'): TasksHomeState => ({
  module: 'tasks',
  day,
  editable: day === 'today',
  tasks: [],
  done: 0,
  total: 0,
  more: 0,
  overdue: 0,
  routines: [],
  saga: null,
})
