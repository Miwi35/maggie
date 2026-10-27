import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'
import { fetchProjectPlanning, PROJECT_TOPIC } from './projectsApi'
import type { ProjectPlanning } from './projectsApi'
import { SCENARIO_TOPIC } from './scenariosApi'

/**
 * What moves the planning and the scenarios drawn on it: the projects, their loans, the safety
 * cushion, the accounts, the scenarios. One list for both, so the Projets page, which shows them
 * together, holds one Mercure stream for them and not two (a browser keeps six connections per origin).
 */
export const PLANNING_TOPICS = [PROJECT_TOPIC, '/api/loans/{id}', '/api/safety_cushions/{id}', '/api/accounts/{id}', SCENARIO_TOPIC]

/** A write publishes several updates at once: one read answers them all. */
const SETTLE_MS = 300

export type PlanningStatus = 'loading' | 'ready' | 'failed'

/**
 * The 36-month planning of the projects (MAG-527), read again when a project, a
 * loan, the cushion or an account is published and on demand after a write. A
 * read that fails keeps the planning already on screen.
 */
export function useProjectPlanning() {
  const [status, setStatus] = useState<PlanningStatus>('loading')
  const [planning, setPlanning] = useState<ProjectPlanning | null>(null)
  const ticket = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const reload = useCallback(async () => {
    const mine = ++ticket.current
    try {
      const next = await fetchProjectPlanning()
      if (mine !== ticket.current) return
      setPlanning(next)
      setStatus('ready')
    } catch {
      if (mine !== ticket.current) return
      setStatus((previous) => (previous === 'ready' ? previous : 'failed'))
    }
  }, [])

  useEffect(() => {
    reload()

    return () => {
      ticket.current++
      if (timer.current) clearTimeout(timer.current)
    }
  }, [reload])

  useMercure(
    PLANNING_TOPICS,
    useCallback(() => {
      if (timer.current) clearTimeout(timer.current)
      timer.current = setTimeout(reload, SETTLE_MS)
    }, [reload]),
  )

  return { status, planning, reload }
}
