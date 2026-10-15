import { apiUrl, headers } from './taskViews'

/** The day holds five tasks at most: a longer list discourages (MAG-497). */
export const DAY_SLOTS = 5

/** A task as the day sends it: only what the day draws. */
export interface DayTask {
  id: string
  title: string
  estimatedMinutes: number | null
  firstStep: string | null
  completedAt: string | null
  dueDate: string | null
  /** `YYYY-MM-DD`: the day it has been put off since; null when it is not. */
  postponedSince: string | null
  urgency: string | null
  criticality: string
}

export interface DaySlot {
  /** 1 to 5, in order. */
  position: number
  type: 'task'
  task: DayTask
}

/** `GET /api/day-plans/{date}`: the slots, the first one not done, and the reserve by score. */
export interface DayPlan {
  id: string
  date: string
  confirmedAt: string | null
  slots: DaySlot[]
  now: DaySlot | null
  reserve: DayTask[]
  done: number
  total: number
}

const dayError = async (response: Response): Promise<Error> => {
  const body = (await response.json().catch(() => ({}))) as { error?: string }

  return new Error(body.error ?? `Journée : ${response.status}`)
}

/** The day of `date` (`YYYY-MM-DD`), created empty by the API on first read. */
export async function fetchDayPlan(date: string): Promise<DayPlan> {
  const response = await fetch(`${apiUrl}/day-plans/${date}`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) {
    throw await dayError(response)
  }

  return (await response.json()) as DayPlan
}

/** Replaces the whole list of the day's tasks, in order; the API answers the day as it stands. */
export async function saveDayPlan(date: string, taskIds: string[]): Promise<DayPlan> {
  const response = await fetch(`${apiUrl}/day-plans/${date}`, {
    method: 'PUT',
    headers: headers({ Accept: 'application/json', 'Content-Type': 'application/json' }),
    body: JSON.stringify({ slots: taskIds.map((taskId) => ({ type: 'task', taskId })) }),
  })
  if (!response.ok) {
    throw await dayError(response)
  }

  return (await response.json()) as DayPlan
}

/** Where a task is put: on the slot at that index, or in the free room after the last one. */
export type DayTarget = number | 'free'

/**
 * The day's list once `taskId` is put on `target`, or null when nothing changes.
 *
 * A task of the day moves there (the others shift); a task of the reserve takes
 * that slot, whose task goes back to the reserve, or fills the free room when
 * there is some.
 */
export function placeOnDay(slots: string[], taskId: string, target: DayTarget): string[] | null {
  const from = slots.indexOf(taskId)

  if (from >= 0) {
    const to = target === 'free' ? slots.length - 1 : Math.min(target, slots.length - 1)
    if (to === from) return null
    const next = slots.filter((id) => id !== taskId)
    next.splice(to, 0, taskId)

    return next
  }

  if (target === 'free' || target >= slots.length) {
    return slots.length < DAY_SLOTS ? [...slots, taskId] : null
  }
  const next = [...slots]
  next[target] = taskId

  return next
}
