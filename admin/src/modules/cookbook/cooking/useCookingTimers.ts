import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMercure } from '../../../hooks/useMercure'
import { idOf } from '../recipeSheet'
import { TIMER_TOPICS, TimerRequestError, changeTimer, fetchTimers, startTimer } from './cookingTimersApi'
import type { TimerAction, TimerStart } from './cookingTimersApi'
import type { CookingTimer, TimerView } from './cookingTimers'

/** How often a timer whose count reached 0 asks the API whether it rang, in case Mercure is late. */
export const RING_POLL_MS = 2000

export const START_ERROR = "Le minuteur n'a pas démarré. Vérifie la connexion, puis réessaie."
export const CHANGE_ERROR = "Le minuteur n'a pas pu être modifié. Vérifie la connexion, puis réessaie."

interface Tracked {
  timer: CookingTimer
  /** When it reaches 0 on this device's clock, from the rest the server gave: no clock skew. Null unless running. */
  deadline: number | null
  /** When this device learnt it: a read that left earlier does not overwrite it. */
  at: number
}

type Board = Record<string, Tracked>

const track = (timer: CookingTimer, at: number = Date.now()): Tracked => ({
  timer,
  deadline: timer.status === 'running' ? at + timer.remainingSeconds * 1000 : null,
  at,
})

/** A Mercure payload: the whole timer, with its `@id`. */
const readPayload = (raw: string | undefined): CookingTimer | null => {
  try {
    const data = JSON.parse(raw ?? '') as Record<string, unknown>
    if (typeof data !== 'object' || data === null || typeof data.status !== 'string' || typeof data.label !== 'string') return null
    const id = typeof data.id === 'string' ? data.id : typeof data['@id'] === 'string' ? idOf(data['@id']) : ''
    if (!id) return null
    const timer = { ...data, id } as unknown as CookingTimer & { '@id'?: unknown }
    delete timer['@id']

    return timer
  } catch {
    return null
  }
}

const without = (board: Board, id: string): Board => {
  const next = { ...board }
  delete next[id]

  return next
}

const remainingOf = ({ timer, deadline }: Tracked, now: number): number => {
  if (timer.status === 'paused') return timer.remainingSeconds
  if (timer.status !== 'running' || deadline === null) return 0

  return Math.max(0, Math.min(timer.remainingSeconds, Math.ceil((deadline - now) / 1000)))
}

export interface CookingTimers {
  /** Every timer of the activity but the cancelled ones, in the order they started; `remaining` ticks every second. */
  timers: TimerView[]
  /** What the last refused start or change says; null when all went through. */
  error: string | null
  clearError: () => void
  /** POST: true once the API holds it. */
  start: (start: TimerStart) => Promise<boolean>
  /** PATCH: pause, resume, ±seconds, cancel, dismiss (« OK »). */
  change: (id: string, action: TimerAction, seconds?: number) => Promise<boolean>
  /** « Quitter »: every running, paused or rung timer stops. */
  cancelAll: () => Promise<void>
}

/**
 * The timers of the Cuisine activity (MAG-582). The API holds them: read on load (`GET`), kept by
 * Mercure, counted down here between two updates. A reload reads them back as they are: nothing
 * restarts, nothing is lost, nothing is posted twice.
 */
export const useCookingTimers = (): CookingTimers => {
  const [board, setBoard] = useState<Board>({})
  const [now, setNow] = useState(() => Date.now())
  const [error, setError] = useState<string | null>(null)
  const boardRef = useRef(board)
  useEffect(() => {
    boardRef.current = board
  })

  const put = useCallback((timer: CookingTimer) => {
    setBoard((previous) => {
      if (timer.status === 'cancelled') {
        return timer.id in previous ? without(previous, timer.id) : previous
      }

      return { ...previous, [timer.id]: track(timer) }
    })
  }, [])

  const forget = useCallback((id: string) => {
    setBoard((previous) => without(previous, id))
  }, [])

  const refresh = useCallback(async (signal?: AbortSignal) => {
    const asked = Date.now()
    const listed = await fetchTimers(signal)
    if (signal?.aborted) return
    const answered = Date.now()
    setBoard((previous) => {
      const fresh = new Map(listed.map((timer) => [timer.id, timer]))
      const next: Board = {}
      for (const [id, known] of Object.entries(previous)) {
        // Learnt after the read left, or dismissed (the read leaves those out, they still unlock what waited).
        if (known.at > asked || (!fresh.has(id) && known.timer.status === 'dismissed')) next[id] = known
        else if (fresh.has(id)) next[id] = track(fresh.get(id)!, answered)
      }
      for (const timer of listed) if (!(timer.id in next)) next[timer.id] = track(timer, answered)

      return next
    })
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    refresh(controller.signal).catch(() => {
      // Offline: the timers come back with the next update or the next read.
    })

    return () => controller.abort()
  }, [refresh])

  useMercure(TIMER_TOPICS, (data) => {
    const timer = readPayload(data)
    if (timer) put(timer)
  })

  const running = Object.values(board).some(({ timer }) => timer.status === 'running')
  useEffect(() => {
    if (!running) return
    const tick = window.setInterval(() => setNow(Date.now()), 1000)

    return () => window.clearInterval(tick)
  }, [running])

  const timers = useMemo(() => Object.values(board).map((tracked) => ({ ...tracked.timer, remaining: remainingOf(tracked, now) })), [board, now])

  // The count reached 0 here: the worker rings it on the server, Mercure says so; if it is late, ask.
  const overdue = timers.some((timer) => timer.status === 'running' && timer.remaining === 0)
  useEffect(() => {
    if (!overdue) return
    const ask = () => {
      refresh().catch(() => {
        // Next round.
      })
    }
    ask()
    const poll = window.setInterval(ask, RING_POLL_MS)

    return () => window.clearInterval(poll)
  }, [overdue, refresh])

  const start = useCallback(
    async (input: TimerStart) => {
      try {
        put(await startTimer(input))
        setError(null)

        return true
      } catch {
        setError(START_ERROR)

        return false
      }
    },
    [put],
  )

  const change = useCallback(
    async (id: string, action: TimerAction, seconds?: number) => {
      try {
        put(await changeTimer(id, action, seconds))
        setError(null)

        return true
      } catch (e) {
        // Refused with the timer as it stands, or gone: the screen shows the truth, nothing to say.
        if (e instanceof TimerRequestError && e.timer) put(e.timer)
        else if (e instanceof TimerRequestError && e.gone) forget(id)
        else setError(CHANGE_ERROR)

        return false
      }
    },
    [forget, put],
  )

  const cancelAll = useCallback(async () => {
    const active = Object.values(boardRef.current)
      .map(({ timer }) => timer)
      .filter((timer) => timer.status === 'running' || timer.status === 'paused' || timer.status === 'done')
    await Promise.allSettled(active.map((timer) => changeTimer(timer.id, 'cancel').then(put)))
  }, [put])

  const clearError = useCallback(() => setError(null), [])

  return { timers, error, clearError, start, change, cancelAll }
}
