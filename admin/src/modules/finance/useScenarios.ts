import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'
import { fetchComparison, fetchScenarios, SCENARIO_TOPIC } from './scenariosApi'
import type { Comparison, Scenario } from './scenariosApi'

const SCENARIO_TOPICS = [SCENARIO_TOPIC]

/** A write publishes several updates at once (the old current one, the new): one read answers them all. */
const SETTLE_MS = 300

export type ScenariosStatus = 'loading' | 'ready' | 'failed'

/** The scenarios, read again when one is published and on demand after a write. A failed read keeps the list on screen. */
export function useScenarios() {
  const [status, setStatus] = useState<ScenariosStatus>('loading')
  const [scenarios, setScenarios] = useState<Scenario[]>([])
  const [version, setVersion] = useState(0)
  const ticket = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const reload = useCallback(async () => {
    const mine = ++ticket.current
    try {
      const next = await fetchScenarios()
      if (mine !== ticket.current) return
      setScenarios(next)
      setStatus('ready')
      setVersion((previous) => previous + 1)
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
    SCENARIO_TOPICS,
    useCallback(() => {
      if (timer.current) clearTimeout(timer.current)
      timer.current = setTimeout(reload, SETTLE_MS)
    }, [reload]),
  )

  return { status, scenarios, version, reload }
}

export type ComparisonStatus = 'idle' | 'loading' | 'ready' | 'failed'

/** The comparison of two to six scenarios, the first being the reference; read again when the list changes. */
export function useScenarioComparison(ids: string[], version: number) {
  const [status, setStatus] = useState<ComparisonStatus>('idle')
  const [comparison, setComparison] = useState<Comparison | null>(null)
  const [error, setError] = useState<string | null>(null)
  const key = ids.join(',')

  useEffect(() => {
    if (ids.length < 2) {
      setStatus('idle')
      setComparison(null)
      return
    }
    let stale = false
    setStatus((previous) => (previous === 'ready' ? previous : 'loading'))
    fetchComparison(ids)
      .then((next) => {
        if (stale) return
        setComparison(next)
        setError(null)
        setStatus('ready')
      })
      .catch((failure: unknown) => {
        if (stale) return
        setComparison(null)
        setError(failure instanceof Error && failure.message ? failure.message : 'La comparaison ne peut pas être chargée.')
        setStatus('failed')
      })

    return () => {
      stale = true
    }
    // `ids` is read through its key.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, version])

  return { status, comparison, error }
}

/** The reference plus five others: the API compares six scenarios at most. */
const OTHERS_PER_BATCH = 5

/**
 * What the API says of each scenario's N against the current one (its own sentence, untouched), by scenario id.
 * Empty when no scenario is current. More than five others are read in batches, each led by the current scenario.
 * A failed read leaves the cards without a gap: this comparison is silent, the visible one reports its own errors.
 */
export function useGapsAgainstCurrent(currentId: string | null, otherIds: string[], version: number) {
  const [gaps, setGaps] = useState<Record<string, string>>({})
  const key = otherIds.join(',')

  useEffect(() => {
    if (!currentId || otherIds.length === 0) {
      setGaps({})
      return
    }
    let stale = false
    const batches: string[][] = []
    for (let start = 0; start < otherIds.length; start += OTHERS_PER_BATCH) {
      batches.push([currentId, ...otherIds.slice(start, start + OTHERS_PER_BATCH)])
    }
    Promise.all(batches.map((ids) => fetchComparison(ids).catch(() => null))).then((answers) => {
      if (stale) return
      const next: Record<string, string> = {}
      for (const answer of answers) {
        for (const difference of answer?.differences ?? []) {
          if (difference.sentence) next[difference.scenarioId] = difference.sentence
        }
      }
      setGaps(next)
    })

    return () => {
      stale = true
    }
    // `otherIds` is read through its key.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentId, key, version])

  return gaps
}
