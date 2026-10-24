import { orderedSteps } from '../recipeSheet'
import type { RecipeRecord } from '../recipeSheet'

/** The route of the activity: `?meal=<id>` or `?recipe=<id>` says what it cooks. */
export const COOKING_ACTIVITY_PATH = '/activites/cuisine'

/** Where quitting goes when the activity was opened by its address. */
export const COOKING_FALLBACK_PATH = '/meals'

/** The Cuisine activity's colour. */
export const COOKING_COLOR = '#8FB8FF'

export const mealLaunchPath = (mealId: string): string => `${COOKING_ACTIVITY_PATH}?meal=${encodeURIComponent(mealId)}`
export const recipeLaunchPath = (recipeId: string): string => `${COOKING_ACTIVITY_PATH}?recipe=${encodeURIComponent(recipeId)}`

export type CookingOrigin = { kind: 'meal' | 'recipe'; id: string }

export interface QueuedRecipe {
  recipeId: string
  /** The number of people this recipe is cooked for: its quantities follow it. */
  servings: number
}

/**
 * Where the activity stands, on the device that runs it. The steps' details (MAG-581) and the
 * shopping list (MAG-583) read `servings` from here.
 */
export interface CookingSession {
  origin: CookingOrigin
  /** The recipes in the order they are cooked: a meal's, or the one recipe. */
  queue: QueuedRecipe[]
  recipeIndex: number
  /** 0 is the preparation, the steps follow from 1. */
  step: number
  guests: number
  /** `servings` asks « Pour combien ? » (launched from a recipe), `done` is « Bon appétit ». */
  phase: 'servings' | 'cooking' | 'done'
}

export const MIN_GUESTS = 1
export const MAX_GUESTS = 999

const clampGuests = (guests: number): number => Math.min(MAX_GUESTS, Math.max(MIN_GUESTS, Math.round(guests)))

/** A meal: its recipes in order, all for the meal's guests, straight to the preparation. */
export const mealSession = (mealId: string, recipeIds: string[], guests: number): CookingSession => {
  const count = clampGuests(guests)

  return {
    origin: { kind: 'meal', id: mealId },
    queue: recipeIds.map((recipeId) => ({ recipeId, servings: count })),
    recipeIndex: 0,
    step: 0,
    guests: count,
    phase: 'cooking',
  }
}

/** A recipe alone: « Pour combien ? » first, filled with the recipe's own servings. */
export const recipeSession = (recipeId: string, servings: number): CookingSession => {
  const count = clampGuests(servings)

  return {
    origin: { kind: 'recipe', id: recipeId },
    queue: [{ recipeId, servings: count }],
    recipeIndex: 0,
    step: 0,
    guests: count,
    phase: 'servings',
  }
}

/** Another number of people: every recipe of the queue follows it (the API keeps one count per meal). */
export const withGuests = (session: CookingSession, guests: number): CookingSession => {
  const count = clampGuests(guests)

  return { ...session, guests: count, queue: session.queue.map((entry) => ({ ...entry, servings: count })) }
}

/** « Continuer » on « Pour combien ? »: the preparation of the recipe. */
export const confirmServings = (session: CookingSession): CookingSession => ({ ...session, phase: 'cooking', step: 0 })

/** The next recipe's preparation, or « Bon appétit » after the last one. */
const nextRecipe = (session: CookingSession): CookingSession =>
  session.recipeIndex + 1 < session.queue.length
    ? { ...session, recipeIndex: session.recipeIndex + 1, step: 0 }
    : { ...session, phase: 'done' }

/** « Suivant »: the next step, then the next recipe. `stepCount` is the current recipe's. */
export const advance = (session: CookingSession, stepCount: number): CookingSession =>
  session.phase !== 'cooking' ? session : session.step < stepCount ? { ...session, step: session.step + 1 } : nextRecipe(session)

/** « Passer »: leaves a recipe that has no step to cook. */
export const skipRecipe = (session: CookingSession): CookingSession => (session.phase === 'cooking' ? nextRecipe(session) : session)

/** « Précédent »: the step before, down to the preparation. */
export const back = (session: CookingSession): CookingSession =>
  session.phase === 'cooking' && session.step > 0 ? { ...session, step: session.step - 1 } : session

const STORAGE_KEY = 'maggie.cooking-session'

/** The session a reload of the page resumes: only when it is the same launch. */
export const loadSession = (origin: CookingOrigin): CookingSession | null => {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const stored = JSON.parse(raw) as CookingSession
    if (stored.origin?.kind !== origin.kind || stored.origin.id !== origin.id) return null
    if (!Array.isArray(stored.queue) || stored.queue.length === 0 || stored.phase === 'done') return null
    if (!Number.isInteger(stored.recipeIndex) || stored.recipeIndex < 0 || stored.recipeIndex >= stored.queue.length) return null

    return stored
  } catch {
    return null
  }
}

export const saveSession = (session: CookingSession): void => {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  } catch {
    // Storage full or refused: the activity still runs, it just does not survive a reload.
  }
}

export const clearSession = (): void => {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing to clear.
  }
}

/** The recipe's steps in cooking order, as plain text. */
export const stepTexts = (recipe: RecipeRecord): string[] => orderedSteps(recipe.steps).map((step) => step.text)
