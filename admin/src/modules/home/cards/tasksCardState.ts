/** `GET /api/home/tasks?day=today|tomorrow` (MAG-561). */
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
