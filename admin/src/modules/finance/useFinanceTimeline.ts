import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchScenarioChoices, fetchTimeline, TimelineError } from './timelineApi'
import type { ScenarioChoice, Timeline } from './timelineApi'

/** Said when the API refuses the comparison: the frise goes back to the current scenario alone. */
export const COMPARE_REFUSED = "Ce scénario ne peut pas être comparé : il est déjà celui de la frise. La comparaison est retirée."
export const COMPARE_UNKNOWN = "Ce scénario n'existe plus. La comparaison est retirée."

/**
 * The 20-year frise and the scenario beside it (MAG-554). Choosing a scenario
 * reads the frise again with `?compare=`; a 400 or a 404 on that read drops the
 * comparison and says why, any other failure keeps what is on screen and offers
 * to read again.
 */
export function useFinanceTimeline() {
  const [timeline, setTimeline] = useState<Timeline | null>(null)
  const [fetching, setFetching] = useState(true)
  const [failed, setFailed] = useState(false)
  const [compareId, setCompareId] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [scenarios, setScenarios] = useState<ScenarioChoice[]>([])
  const [scenariosFailed, setScenariosFailed] = useState(false)
  const ticket = useRef(0)

  const load = useCallback(async (compare: string | null) => {
    const mine = ++ticket.current
    setFetching(true)
    try {
      const next = await fetchTimeline(compare)
      if (mine !== ticket.current) return
      setTimeline(next)
    } catch (error) {
      if (mine !== ticket.current) return
      const code = error instanceof TimelineError ? error.status : 0
      if (compare !== null && (code === 400 || code === 404)) {
        setNotice(code === 400 ? COMPARE_REFUSED : COMPARE_UNKNOWN)
        // The effect below reads the frise again, without the comparison.
        setCompareId(null)

        return
      }
      setFailed(true)
    } finally {
      if (mine === ticket.current) setFetching(false)
    }
  }, [])

  useEffect(() => {
    setFailed(false)
    load(compareId)
  }, [compareId, load])

  useEffect(() => {
    let alive = true
    fetchScenarioChoices()
      .then((choices) => alive && setScenarios(choices))
      .catch(() => alive && setScenariosFailed(true))

    return () => {
      alive = false
    }
  }, [])

  const compareWith = useCallback((id: string | null) => {
    setNotice(null)
    setCompareId(id)
  }, [])

  const reload = useCallback(() => {
    setFailed(false)
    load(compareId)
  }, [compareId, load])

  return { timeline, fetching, failed, compareId, compareWith, notice, scenarios, scenariosFailed, reload }
}
