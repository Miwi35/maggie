import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { RecipeRecord } from '../recipeSheet'
import { fetchMeal, fetchRecipe, recipeIdsOf } from './cookingApi'
import { clearSession, loadSession, mealSession, recipeSession, restartSession, saveSession, sessionFromState, stateOf } from './cookingSession'
import type { CookingOrigin, CookingSession } from './cookingSession'
import { fetchCookingStatus } from './cookingStatusApi'
import type { CookingStatus } from './cookingStatusApi'
import { createStatusWriter } from './cookingStatusWriter'

/** `conflict`: another meal or recipe is already being cooked, and the owner chooses what happens to it. */
export type CookingLoad = 'loading' | 'error' | 'empty' | 'ready' | 'conflict'

export interface CookingActivity {
  /** `empty` is a meal with no recipe; `error` an unreachable API, which never closes the activity. */
  load: CookingLoad
  session: CookingSession | null
  /** The activity in progress when `load` is `conflict`. */
  conflict: CookingStatus | null
  /** The recipes of the queue by id, scaled to the session's servings once the preparation is reached. */
  recipes: Record<string, RecipeRecord>
  /** True while the recipes of the current servings are still on their way. */
  refreshing: boolean
  retry: () => void
  update: (change: (session: CookingSession) => CookingSession) => void
  /** Leaves the activity: its status stays on the API, only this tab's copy goes. */
  leave: () => void
  /** « Recommencer »: the preparation again, the status erased. */
  restart: () => void
  /** The owner chose this launch over the one in progress: that one is erased and this one starts. */
  replace: () => void
}

/**
 * Reads what the activity cooks (`GET /api/meals/{id}`, `GET /api/recipes/{id}?guests=N`) and keeps
 * the session in `activity-status` (MAG-615), written at every change, so any device resumes where it
 * was. The tab keeps a copy in `sessionStorage` so reloading shows it at once.
 */
export const useCookingActivity = (origin: CookingOrigin): CookingActivity => {
  const { kind, id } = origin
  const [attempt, setAttempt] = useState(0)
  const [load, setLoad] = useState<CookingLoad>('loading')
  const [session, setSession] = useState<CookingSession | null>(null)
  const [conflict, setConflict] = useState<CookingStatus | null>(null)
  const [recipes, setRecipes] = useState<Record<string, RecipeRecord>>({})
  const [refreshing, setRefreshing] = useState(false)
  const sessionRef = useRef<CookingSession | null>(null)
  const [writer] = useState(createStatusWriter)

  const apply = useCallback((next: CookingSession | null) => {
    sessionRef.current = next
    setSession(next)
  }, [])

  /** The launch as it begins: a meal at its preparation, a recipe at « Pour combien ? ». */
  const begin = useCallback(
    async (signal: AbortSignal): Promise<boolean> => {
      if (kind === 'meal') {
        const meal = await fetchMeal(id, signal)
        const recipeIds = recipeIdsOf(meal)
        if (recipeIds.length === 0) {
          setLoad('empty')

          return false
        }
        apply(mealSession(id, recipeIds, meal.guests))
      } else {
        const recipe = await fetchRecipe(id, null, signal)
        apply(recipeSession(id, recipe.servings ?? 1))
        setRecipes({ [id]: recipe })
      }
      if (sessionRef.current) saveSession(sessionRef.current)

      return true
    },
    [apply, id, kind],
  )

  useEffect(() => {
    const controller = new AbortController()
    const { signal } = controller
    setLoad('loading')
    setConflict(null)

    const bootstrap = async () => {
      const cached = loadSession({ kind, id })
      if (cached) {
        apply(cached)
        setLoad('ready')

        return
      }
      const status = await fetchCookingStatus(signal)
      if (status && (status.origin.kind !== kind || status.origin.id !== id)) {
        setConflict(status)
        setLoad('conflict')

        return
      }
      const resumed = status ? sessionFromState({ kind, id }, status.state) : null
      if (resumed) {
        apply(resumed)
        saveSession(resumed)
        setLoad('ready')

        return
      }
      if (await begin(signal)) setLoad('ready')
    }

    bootstrap().catch(() => {
      if (!signal.aborted) setLoad('error')
    })

    return () => controller.abort()
  }, [kind, id, attempt, apply, begin])

  // Before « Pour combien ? » is answered the recipe is read as it is; after, once per number of people.
  const phase = session?.phase
  const wanted = useMemo(() => {
    if (!session || phase === 'done') return ''

    return session.queue.map((entry) => `${entry.recipeId}:${phase === 'servings' ? '' : entry.servings}`).join('|')
  }, [session, phase])

  useEffect(() => {
    if (!wanted) return
    const controller = new AbortController()
    const { signal } = controller
    setRefreshing(true)

    Promise.all(
      wanted.split('|').map(async (key) => {
        const [recipeId, servings] = key.split(':')
        const recipe = await fetchRecipe(recipeId, servings === '' ? null : Number(servings), signal)

        return [recipeId, recipe] as const
      }),
    )
      .then((entries) => {
        if (signal.aborted) return
        setRecipes((previous) => ({ ...previous, ...Object.fromEntries(entries) }))
        setRefreshing(false)
      })
      .catch(() => {
        if (signal.aborted) return
        setRefreshing(false)
        setLoad('error')
      })

    return () => controller.abort()
  }, [wanted, attempt])

  const update = useCallback(
    (change: (session: CookingSession) => CookingSession) => {
      const current = sessionRef.current
      if (!current) return
      const next = change(current)
      if (next === current) return
      apply(next)
      saveSession(next)
      // Served: the activity is over, nothing to resume.
      if (next.phase === 'done') writer.clear()
      else writer.save(next.origin, stateOf(next))
    },
    [apply, writer],
  )

  const leave = useCallback(() => clearSession(), [])

  const restart = useCallback(() => {
    const current = sessionRef.current
    if (!current) return
    const next = restartSession(current)
    apply(next)
    saveSession(next)
    writer.clear()
  }, [apply, writer])

  const replace = useCallback(() => {
    writer.clear()
    clearSession()
    setConflict(null)
    setLoad('loading')
    begin(new AbortController().signal)
      .then((started) => {
        if (started) setLoad('ready')
      })
      .catch(() => setLoad('error'))
  }, [begin, writer])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  return { load, session, conflict, recipes, refreshing, retry, update, leave, restart, replace }
}
