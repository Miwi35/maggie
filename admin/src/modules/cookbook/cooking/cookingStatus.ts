import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../../hooks/useMercure'
import { launchPathOf, sessionFromState } from './cookingSession'
import type { CookingSession } from './cookingSession'
import { COOKING_STATUS_TOPICS, fetchCookingStatus } from './cookingStatusApi'
import type { CookingStatus } from './cookingStatusApi'
import { fetchTimers } from './cookingTimersApi'
import { isTicking, timersOfRecipe } from './cookingTimers'
import type { CookingTimer } from './cookingTimers'

/** « 1 h 47 », « 12 min », « 40 s »: what is left on a timer, short enough to sit beside a button. */
export const restOf = (seconds: number): string => {
  const total = Math.max(0, Math.round(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  if (hours > 0) return `${hours} h ${String(minutes).padStart(2, '0')}`

  return minutes > 0 ? `${minutes} min` : `${total} s`
}

const secondsLeft = (timer: CookingTimer, now: number): number =>
  timer.status === 'running' && timer.endsAt ? Math.max(0, (Date.parse(timer.endsAt) - now) / 1000) : timer.remainingSeconds

const lowerFirst = (text: string): string => text.charAt(0).toLowerCase() + text.slice(1)

/**
 * « étape 7/11 · mijotage 1 h 47 »: where the recipe stands in the activity in progress, and its timer
 * that rings next (the current step's first). Nothing when the activity is on another recipe of the
 * meal, or still asking « Pour combien ? ».
 */
export const resumeDetail = (session: CookingSession, recipeId: string, stepCount: number, timers: CookingTimer[], now: number): string | undefined => {
  if (session.queue[session.recipeIndex]?.recipeId !== recipeId || session.phase !== 'cooking') return undefined

  const place = session.step === 0 ? 'préparation' : `étape ${session.step}/${stepCount}`
  const ticking = timersOfRecipe(timers, recipeId).filter(isTicking)
  const timer =
    ticking.find((candidate) => candidate.stepPosition === session.step) ??
    [...ticking].sort((a, b) => secondsLeft(a, now) - secondsLeft(b, now))[0]

  return timer ? `${place} · ${lowerFirst(timer.label)} ${restOf(secondsLeft(timer, now))}` : place
}

export interface Resume {
  /** The activity, opened on the origin it was launched from. */
  to: string
  detail?: string
}

/** « ▶ Reprendre » on a recipe's sheet: when the activity in progress cooks this recipe. */
export const resumeOf = (status: CookingStatus | null, recipeId: string, stepCount: number, timers: CookingTimer[], now: number): Resume | null => {
  if (!status) return null
  const session = sessionFromState(status.origin, status.state)
  if (!session || !session.queue.some((entry) => entry.recipeId === recipeId)) return null

  return { to: launchPathOf(status.origin), detail: resumeDetail(session, recipeId, stepCount, timers, now) }
}

/** The pill of a meal says « Reprendre » when the activity in progress was launched from that meal. */
export const resumesMeal = (status: CookingStatus | null, mealId: string): boolean => status?.origin.kind === 'meal' && status.origin.id === mealId

export interface CookingStatusView {
  status: CookingStatus | null
  loaded: boolean
}

/** The activity in progress, read on mount and again each time the API says it changed (another device included). */
export const useCookingStatus = (): CookingStatusView => {
  const [view, setView] = useState<CookingStatusView>({ status: null, loaded: false })
  const asked = useRef(0)

  const read = useCallback((signal?: AbortSignal) => {
    const attempt = ++asked.current
    fetchCookingStatus(signal)
      .then((status) => {
        if (attempt === asked.current && !signal?.aborted) setView({ status, loaded: true })
      })
      .catch(() => {
        // Offline: the launch buttons keep to « Cuisiner » until the next message.
      })
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    read(controller.signal)

    return () => controller.abort()
  }, [read])

  useMercure(COOKING_STATUS_TOPICS, () => read())

  return view
}

/** The « ▶ Reprendre » of a recipe's sheet, with its timers; null when the activity in progress is not about this recipe. */
export const useCookingResume = (recipeId: string | null, stepCount: number): Resume | null => {
  const { status } = useCookingStatus()
  const [read, setRead] = useState<{ timers: CookingTimer[]; at: number }>({ timers: [], at: 0 })
  const involved = recipeId !== null && resumeOf(status, recipeId, stepCount, [], 0) !== null

  useEffect(() => {
    if (!involved) return
    const controller = new AbortController()
    fetchTimers(controller.signal)
      .then((listed) => {
        if (!controller.signal.aborted) setRead({ timers: listed, at: Date.now() })
      })
      .catch(() => {
        // Without the timers the detail keeps to the step.
      })

    return () => controller.abort()
  }, [involved, status])

  return recipeId === null ? null : resumeOf(status, recipeId, stepCount, read.timers, read.at)
}
