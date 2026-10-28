import { useCallback, useEffect, useRef, useState } from 'react'
import { localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { fetchDayPlan } from './dayPlan'
import { fetchTaskView, TASK_TOPICS } from './taskViews'
import type { TaskCounts } from './taskViews'

const NO_TOPICS: string[] = []

/**
 * The numbers of the menu's « Tâches » views (MAG-644): « Aujourd'hui » is what is left to do of the day,
 * the others the size of their view. Read only while `enabled` — the menu draws them under the module
 * the page belongs to — and again when a task or the day changes. Null until the first answer, or when
 * the API does not answer: the entries are then drawn without a number.
 */
export function useTaskCounts(enabled: boolean): TaskCounts | null {
  const [counts, setCounts] = useState<TaskCounts | null>(null)
  const request = useRef(0)

  const load = useCallback(() => {
    const current = ++request.current
    // « Faites » is the one view the API pages: its first page is the lightest answer that carries every count.
    Promise.all([fetchTaskView('done'), fetchDayPlan(localDay(new Date()))])
      .then(([view, day]) => {
        if (current === request.current) setCounts({ ...view.counts, today: day.total - day.done })
      })
      .catch(() => {
        if (current === request.current) setCounts(null)
      })
  }, [])

  useEffect(() => {
    if (enabled) load()
  }, [enabled, load])

  useMercure(enabled ? TASK_TOPICS : NO_TOPICS, load)

  return enabled ? counts : null
}
