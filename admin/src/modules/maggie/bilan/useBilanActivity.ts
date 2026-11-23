import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../../hooks/useMercure'
import { BILAN_STATUS_TOPICS, createBilanWriter, fetchBilanStatus } from './bilanStatusApi'
import type { BilanWriter } from './bilanStatusApi'
import { isPrepared } from './bilanState'
import { answersOf, newSession, originOf, resumable, sessionOf, stateOf } from './bilanSession'
import type { BilanAnswers, BilanSession, BilanStep } from './bilanSession'

/** `error`: the API cannot be reached, which never closes the activity; `closed`: the bilan is over (Bonne nuit, or gone). */
export type BilanLoad = 'loading' | 'error' | 'ready' | 'closed'

export interface BilanActivity {
  load: BilanLoad
  session: BilanSession | null
  retry: () => void
  moveTo: (step: BilanStep) => void
  /** Merges answers into `answers` (the other answers stay untouched) and writes the status. */
  record: (answers: BilanAnswers | ((session: BilanSession) => BilanAnswers)) => void
  /** Rewrites the status' state: `change` returns the keys to replace (`tomorrowTasks`, `answers`…), the others stay untouched. */
  amend: (change: (session: BilanSession) => Record<string, unknown>) => void
  /** « Bonne nuit »: `plan` runs first and a refusal (false) keeps the bilan open; then the status is erased and the activity closes. */
  end: (plan?: () => Promise<boolean>) => Promise<boolean>
}

/**
 * Opens the bilan of `today` or resumes the one in progress (MAG-747), writes every change to
 * `activity-status` so any device resumes where it was, and adopts what another device or Maggie
 * wrote. The API is read first, so another device's progress is never overwritten.
 */
export const useBilanActivity = (today: string, writer?: BilanWriter): BilanActivity => {
  const [own] = useState(() => writer ?? createBilanWriter())
  const [attempt, setAttempt] = useState(0)
  const [load, setLoad] = useState<BilanLoad>('loading')
  const [session, setSession] = useState<BilanSession | null>(null)
  const sessionRef = useRef<BilanSession | null>(null)
  const writes = useRef(0)

  const apply = useCallback((next: BilanSession | null) => {
    sessionRef.current = next
    setSession(next)
  }, [])

  const save = useCallback(
    (next: BilanSession) => {
      writes.current++
      apply(next)
      own.save(originOf(next), stateOf(next))
    },
    [apply, own],
  )

  useEffect(() => {
    const controller = new AbortController()
    setLoad('loading')

    const open = async () => {
      await own.settled()
      const status = await fetchBilanStatus(controller.signal)
      const resumed = resumable(status, today)
      if (controller.signal.aborted) return
      // A bilan Maggie prepared has no step: it opens at Journée and keeps what she chose.
      if (resumed) apply(resumed)
      else if (isPrepared(status, today)) save({ day: today, step: 'journee', state: status.state })
      else save(newSession(today))
      setLoad('ready')
    }

    open().catch(() => {
      if (!controller.signal.aborted) setLoad('error')
    })

    return () => controller.abort()
  }, [today, attempt, apply, save, own])

  // What another device (or Maggie) wrote, or the end of the bilan. A write of this tab made meanwhile wins.
  const adopt = useCallback(async () => {
    if (!sessionRef.current) return
    const stamp = writes.current
    try {
      await own.settled()
      if (writes.current !== stamp) return
      const status = await fetchBilanStatus()
      if (writes.current !== stamp) return
      if (status === null) {
        apply(null)
        setLoad('closed')

        return
      }
      const stored = sessionOf(status)
      if (stored) apply(stored)
    } catch {
      // Offline: the next message reads again.
    }
  }, [apply, own])

  useMercure(BILAN_STATUS_TOPICS, () => void adopt())

  const moveTo = useCallback(
    (step: BilanStep) => {
      const current = sessionRef.current
      if (!current || current.step === step) return
      save({ ...current, step })
    },
    [save],
  )

  const record = useCallback(
    (answers: BilanAnswers | ((session: BilanSession) => BilanAnswers)) => {
      const current = sessionRef.current
      if (!current) return
      const patch = typeof answers === 'function' ? answers(current) : answers
      save({ ...current, state: { ...current.state, answers: { ...answersOf(current), ...patch } } })
    },
    [save],
  )

  const amend = useCallback(
    (change: (session: BilanSession) => Record<string, unknown>) => {
      const current = sessionRef.current
      if (!current) return
      save({ ...current, state: { ...current.state, ...change(current) } })
    },
    [save],
  )

  const end = useCallback(
    async (plan?: () => Promise<boolean>): Promise<boolean> => {
      if (plan && !(await plan())) return false
      writes.current++
      own.clear()
      apply(null)
      setLoad('closed')

      return true
    },
    [apply, own],
  )

  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  return { load, session, retry, moveTo, record, amend, end }
}
