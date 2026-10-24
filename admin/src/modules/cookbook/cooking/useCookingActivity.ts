import { useCallback, useEffect, useMemo, useState } from 'react'
import type { RecipeRecord } from '../recipeSheet'
import { fetchMeal, fetchRecipe, recipeIdsOf } from './cookingApi'
import { clearSession, loadSession, mealSession, recipeSession, saveSession } from './cookingSession'
import type { CookingOrigin, CookingSession } from './cookingSession'

export type CookingLoad = 'loading' | 'error' | 'empty' | 'ready'

export interface CookingActivity {
  /** `empty` is a meal with no recipe; `error` an unreachable API, which never closes the activity. */
  load: CookingLoad
  session: CookingSession | null
  /** The recipes of the queue by id, scaled to the session's servings once the preparation is reached. */
  recipes: Record<string, RecipeRecord>
  /** True while the recipes of the current servings are still on their way. */
  refreshing: boolean
  retry: () => void
  update: (change: (session: CookingSession) => CookingSession) => void
  /** Forgets the session: the activity is over. */
  end: () => void
}

/**
 * Reads what the activity cooks (`GET /api/meals/{id}`, `GET /api/recipes/{id}?guests=N`) and keeps
 * the session: on the device, in `sessionStorage`, so reloading the page resumes where it was.
 */
export const useCookingActivity = (origin: CookingOrigin): CookingActivity => {
  const { kind, id } = origin
  const [attempt, setAttempt] = useState(0)
  const [load, setLoad] = useState<CookingLoad>('loading')
  const [session, setSession] = useState<CookingSession | null>(null)
  const [recipes, setRecipes] = useState<Record<string, RecipeRecord>>({})
  const [refreshing, setRefreshing] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    const { signal } = controller
    setLoad('loading')

    const bootstrap = async () => {
      const stored = loadSession({ kind, id })
      if (stored) {
        setSession(stored)
        setLoad('ready')

        return
      }
      if (kind === 'meal') {
        const meal = await fetchMeal(id, signal)
        const recipeIds = recipeIdsOf(meal)
        if (recipeIds.length === 0) {
          setLoad('empty')

          return
        }
        const started = mealSession(id, recipeIds, meal.guests)
        saveSession(started)
        setSession(started)
      } else {
        const recipe = await fetchRecipe(id, null, signal)
        const started = recipeSession(id, recipe.servings ?? 1)
        saveSession(started)
        setSession(started)
        setRecipes({ [id]: recipe })
      }
      setLoad('ready')
    }

    bootstrap().catch(() => {
      if (!signal.aborted) setLoad('error')
    })

    return () => controller.abort()
  }, [kind, id, attempt])

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

  const update = useCallback((change: (current: CookingSession) => CookingSession) => {
    setSession((current) => {
      if (!current) return current
      const next = change(current)
      saveSession(next)

      return next
    })
  }, [])

  const end = useCallback(() => clearSession(), [])
  const retry = useCallback(() => setAttempt((count) => count + 1), [])

  return { load, session, recipes, refreshing, retry, update, end }
}
