import { getToken } from '../../auth/session'

const apiUrl = (import.meta.env.VITE_API_URL || 'http://localhost/api').replace(/\/$/, '')

/** A task of the « Aujourd'hui » view, as the API sends it (MAG-405). */
export interface TodayTask {
  '@id': string
  title: string
  criticality: string
  /** The original due day, `YYYY-MM-DD`: a late task is never moved. */
  dueDate?: string | null
  completedAt?: string | null
  /** Days late; null or absent when the task is not late. */
  overdueDays?: number | null
  /** IRI of the task it is a subtask of. */
  parent?: string | null
}

/**
 * The tasks not done that are due today or late — the most late first, each
 * followed by its subtasks. The API decides which day it is (the user's
 * timezone), so the page does not.
 */
export async function fetchTodayTasks(): Promise<TodayTask[]> {
  const token = getToken()
  const response = await fetch(`${apiUrl}/tasks/view/today`, {
    headers: {
      Accept: 'application/ld+json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
  if (!response.ok) {
    throw new Error(`Tâches du jour : ${response.status}`)
  }

  return ((await response.json()) as { member: TodayTask[] }).member
}
