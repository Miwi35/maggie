import { getToken } from '../../auth/session'
import { daysBetween, parseDay } from '../../dates'
import type { TaskData } from './TaskEditDialog'

export const apiUrl = (import.meta.env.VITE_API_URL || 'http://localhost/api').replace(/\/$/, '')

/** The four views of the « Tâches » page, in the order of its tabs (MAG-408). */
export const TASK_VIEWS = ['today', 'upcoming', 'someday', 'done'] as const
export type TaskView = (typeof TASK_VIEWS)[number]

/** A task as the views send it: the form's fields, and what the API computes. */
export interface ViewTask extends TaskData {
  '@id'?: string
  /** Whole days late; null or absent when the task is not late. */
  overdueDays?: number | null
  orderScore?: number | null
}

export type TaskCounts = Record<TaskView, number>

export interface TaskViewResult {
  member: ViewTask[]
  /** The size of every view, from the same response. */
  counts: TaskCounts
  /** The size of this whole view (the « Faites » tab is paged). */
  totalItems: number
}

/** An API answer that was not OK, with its status — the Accueil tells an absent module (404) from a failure. */
export class ApiStatusError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

export const headers = (extra: Record<string, string> = {}): Record<string, string> => {
  const token = getToken()

  return { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extra }
}

/** `done` is paged by the API; the other views come whole. */
export async function fetchTaskView(view: TaskView, page = 1): Promise<TaskViewResult> {
  const query = view === 'done' ? `?page=${page}&itemsPerPage=30` : ''
  const response = await fetch(`${apiUrl}/tasks/view/${view}${query}`, { headers: headers() })
  if (!response.ok) {
    throw new ApiStatusError(`Tâches : ${response.status}`, response.status)
  }
  const body = (await response.json()) as Partial<TaskViewResult>

  return {
    member: body.member ?? [],
    counts: { today: 0, upcoming: 0, someday: 0, done: 0, ...body.counts },
    totalItems: body.totalItems ?? 0,
  }
}

const isTask = (task: ViewTask, id: string): boolean => task.id === id || task['@id']?.split('/').pop() === id

/**
 * The view (and page of « Faites ») that lists the task `id`, among the views the day does not draw;
 * null when none does — a task that does not exist, or is not the user's.
 */
export async function findTaskView(id: string): Promise<{ view: TaskView; page: number } | null> {
  for (const view of TASK_VIEWS) {
    if (view === 'today') continue
    let page = 1
    for (;;) {
      const result = await fetchTaskView(view, page)
      if (result.member.some((task) => isTask(task, id))) return { view, page }
      if (view !== 'done' || page * 30 >= result.totalItems) break
      page++
    }
  }

  return null
}

async function fetchMembers(query: URLSearchParams): Promise<ViewTask[]> {
  const response = await fetch(`${apiUrl}/tasks?${query.toString()}`, { headers: headers() })
  if (!response.ok) {
    throw new ApiStatusError(`Tâches : ${response.status}`, response.status)
  }
  const body = (await response.json()) as { member?: ViewTask[] }

  return body.member ?? []
}

/** The tasks ticked off in [`after`, `before`[ (ISO instants), the latest first. */
export function fetchCompletedBetween(after: string, before: string, limit: number): Promise<ViewTask[]> {
  return fetchMembers(
    new URLSearchParams({
      'completedAt[after]': after,
      'completedAt[strictly_before]': before,
      'order[completedAt]': 'desc',
      itemsPerPage: String(limit),
    }),
  )
}

/** The open tasks due on `day` (`YYYY-MM-DD`), in no particular order. */
export function fetchOpenDueOn(day: string): Promise<ViewTask[]> {
  return fetchMembers(
    new URLSearchParams({
      'exists[completedAt]': 'false',
      'dueDate[after]': day,
      'dueDate[before]': day,
      itemsPerPage: '100',
    }),
  )
}

/** Ticks a task off (`done`) or reopens it — one merge-patch on `completedAt`. */
export async function setTaskDone(id: string, done: boolean): Promise<void> {
  const response = await fetch(`${apiUrl}/tasks/${id.split('/').pop()}`, {
    method: 'PATCH',
    headers: headers({ 'Content-Type': 'application/merge-patch+json' }),
    body: JSON.stringify({ completedAt: done ? new Date().toISOString() : null }),
  })
  if (!response.ok) {
    throw new Error(`Tâche : ${response.status}`)
  }
}

/** Creates a task from its title, and its due day (`YYYY-MM-DD`) when it has one; resolves to its id. */
export async function createTask(title: string, dueDate?: string): Promise<string> {
  const response = await fetch(`${apiUrl}/tasks`, {
    method: 'POST',
    headers: headers({ 'Content-Type': 'application/ld+json' }),
    body: JSON.stringify({ title, ...(dueDate ? { dueDate } : {}) }),
  })
  if (!response.ok) {
    throw new Error(`Tâche : ${response.status}`)
  }
  const body = (await response.json().catch(() => ({}))) as { id?: string }

  return String(body.id ?? '')
}

/** The whole task, as the edit form wants it — the day only carries what it draws. */
export async function fetchTask(id: string): Promise<ViewTask> {
  const response = await fetch(`${apiUrl}/tasks/${id.split('/').pop()}`, { headers: headers() })
  if (!response.ok) {
    throw new Error(`Tâche : ${response.status}`)
  }

  return (await response.json()) as ViewTask
}

/**
 * « reportée depuis … » (MAG-497), from the day the API says a task has been
 * put off since: « hier », a weekday within the week, a date beyond. Never a
 * count of days late — a postponed task is not a reproach.
 */
export function postponedLabel(since: string | null | undefined, today: string): string | null {
  if (!since) return null
  const days = daysBetween(since, today)
  if (days <= 0) return null
  if (days === 1) return 'reportée depuis hier'
  const day = parseDay(since)
  if (days < 7) return `reportée depuis ${day.toLocaleDateString('fr-FR', { weekday: 'long' }).toLowerCase()}`

  return `reportée depuis le ${day.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })}`
}
