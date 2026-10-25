import { idOf } from '../recipeSheet'
import type { RecipeStep } from '../recipeSheet'

export type CookingTimerStatus = 'running' | 'paused' | 'done' | 'dismissed' | 'cancelled'

/** A timer as the API spells it (MAG-582), in every answer and every Mercure payload. */
export interface CookingTimer {
  id: string
  label: string
  /** `/api/recipes/{id}`, or null once the recipe is gone. */
  recipe: string | null
  stepPosition: number
  durationSeconds: number
  /** Null while paused. */
  endsAt: string | null
  /** What was left when the server answered. */
  remainingSeconds: number
  status: CookingTimerStatus
  finishedAt: string | null
}

/** A timer as the screen draws it: `remaining` counts down locally, every second. */
export interface TimerView extends CookingTimer {
  remaining: number
}

/** Running or paused: what « Quitter » stops. */
export const isTicking = (timer: CookingTimer): boolean => timer.status === 'running' || timer.status === 'paused'

const ARTICLES = new Set(['le', 'la', 'les', 'un', 'une', 'des', 'du', 'de'])
const ELIDED = /^([ld])['’](.+)$/i
const PREPOSITIONS = new Set(['à', 'au', 'aux', 'dans', 'avec', 'en', 'pour', 'sur', 'sous', "jusqu'à", 'jusqu’à'])
const PUNCTUATION = /[,;.:!?…()]/
const LABEL_WORDS = 3

/**
 * The name of a step's timer, from its text: the words after the first article, up to a preposition
 * or a punctuation mark, three at most. « Mets l'eau à bouillir. » is « Eau », « Plonge les pâtes dans
 * l'eau bouillante. » is « Pâtes »; no article, the first word; nothing, « Étape {n} ».
 */
export const timerLabel = (text: string, position: number): string => {
  const words = text.trim().split(/\s+/).filter(Boolean)
  const start = words.findIndex((word) => ARTICLES.has(word.toLowerCase()) || ELIDED.test(word))

  const picked: string[] = []
  let first = true
  for (const raw of start >= 0 ? words.slice(start) : words) {
    let word = raw
    if (first) {
      // The article itself is not the name: « l'eau » gives « eau », « de la crème » gives « crème ».
      const elided = ELIDED.exec(word)
      if (elided) word = elided[2]
      else if (start >= 0 && ARTICLES.has(word.toLowerCase())) continue
    }
    if (PREPOSITIONS.has(word.toLowerCase())) break
    const cut = word.search(PUNCTUATION)
    const kept = cut >= 0 ? word.slice(0, cut) : word
    if (kept) picked.push(kept)
    first = false
    // No article: the first word alone.
    if (cut >= 0 || start < 0 || picked.length === LABEL_WORDS) break
  }

  const label = picked.join(' ')

  return label ? label.charAt(0).toUpperCase() + label.slice(1) : `Étape ${position}`
}

/** « 09:12 »; an hour and more reads « 1:05:00 ». */
export const clockOf = (seconds: number): string => {
  const total = Math.max(0, Math.round(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const rest = String(total % 60).padStart(2, '0')

  return hours > 0 ? `${hours}:${minutes}:${rest}` : `${minutes}:${rest}`
}

/** The seconds a step's timer lasts, 0 for a step without a duration. */
export const stepSeconds = (step: RecipeStep | undefined): number =>
  step?.durationMinutes && step.durationMinutes > 0 ? step.durationMinutes * 60 : 0

/** The timers of one recipe of the queue. */
export const timersOfRecipe = <T extends CookingTimer>(timers: T[], recipeId: string): T[] =>
  timers.filter((timer) => timer.recipe !== null && idOf(timer.recipe) === recipeId)

/** A step's timer: the last one started for it, a dismissed one only when no other is left. */
export const stepTimer = <T extends CookingTimer>(timers: T[], position: number): T | undefined => {
  const mine = timers.filter((timer) => timer.stepPosition === position && timer.status !== 'cancelled')

  return [...mine].reverse().find((timer) => timer.status !== 'dismissed') ?? mine[mine.length - 1]
}

/** The timer a step's line shows: running, paused or rung; none once acknowledged. */
export const lineTimer = <T extends CookingTimer>(timers: T[], position: number): T | undefined => {
  const timer = stepTimer(timers, position)

  return timer && timer.status !== 'dismissed' ? timer : undefined
}

/** « 2 minuteurs tournent encore (Pâtes, Légumes) : ils s'arrêteront. » */
export const quitDetail = (timers: CookingTimer[]): string | undefined => {
  const ticking = timers.filter(isTicking)
  if (ticking.length === 0) return undefined
  const labels = ticking.map((timer) => timer.label).join(', ')

  return ticking.length === 1
    ? `1 minuteur tourne encore (${labels}) : il s'arrêtera.`
    : `${ticking.length} minuteurs tournent encore (${labels}) : ils s'arrêteront.`
}
