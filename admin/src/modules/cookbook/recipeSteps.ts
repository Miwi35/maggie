import { arrayMove } from '@dnd-kit/sortable'

// A name the API serves as `{id, name}` and the form edits as plain text.
export type NameRef = string | { id?: string; name: string }

// A step as the form holds it: the API's shape, with a client `key` until the API gives the step an `id`.
export interface RecipeStepDraft {
  id?: string
  key?: string
  text: string
  durationMinutes: number | null
  background: boolean
  ingredients: NameRef[]
  utensils: NameRef[]
  // Positions (1-based) of earlier steps.
  waitsFor: number[]
}

export const nameOf = (ref: NameRef): string => (typeof ref === 'string' ? ref : ref.name)

// An ingredient a step uses, as the API reads it: by id when it has one, so a renamed product keeps its steps.
export const refOf = (ref: NameRef): string => (typeof ref === 'string' ? ref : (ref.id ?? ref.name))

export const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase()

export const dragId = (step: RecipeStepDraft, index: number): string => step.id ?? step.key ?? `step-${index}`

let counter = 0
export const newStep = (): RecipeStepDraft => ({
  key: `new-${++counter}`,
  text: '',
  durationMinutes: null,
  background: false,
  ingredients: [],
  utensils: [],
  waitsFor: [],
})

const dependencies = (positions: number[], own: number, newPosition: (old: number) => number | null): number[] => {
  const kept = new Set<number>()
  for (const old of positions) {
    const moved = newPosition(old)
    if (moved !== null && moved < own) kept.add(moved)
  }
  return [...kept].sort((a, b) => a - b)
}

/**
 * Moves a step; the dependencies follow their steps (positions are recomputed) and a step that
 * now comes before something it waits for no longer waits for it.
 */
export const moveStep = (steps: RecipeStepDraft[], from: number, to: number): RecipeStepDraft[] => {
  if (from === to || from < 0 || to < 0 || from >= steps.length || to >= steps.length) return steps
  const order = arrayMove(
    steps.map((_, index) => index),
    from,
    to,
  )
  const newPositionOfOld = new Map(order.map((oldIndex, newIndex) => [oldIndex + 1, newIndex + 1]))
  return order.map((oldIndex, newIndex) => ({
    ...steps[oldIndex],
    waitsFor: dependencies(steps[oldIndex].waitsFor, newIndex + 1, (old) => newPositionOfOld.get(old) ?? null),
  }))
}

/** Removes a step; the later ones shift up and nobody waits for it any more. */
export const removeStep = (steps: RecipeStepDraft[], index: number): RecipeStepDraft[] =>
  steps
    .filter((_, i) => i !== index)
    .map((step, i) => ({
      ...step,
      waitsFor: dependencies(step.waitsFor, i + 1, (old) => (old === index + 1 ? null : old > index + 1 ? old - 1 : old)),
    }))

/** A duration is a whole number of minutes; "en fond" only makes sense with one. */
export const setDuration = (step: RecipeStepDraft, minutes: number | null): RecipeStepDraft => ({
  ...step,
  durationMinutes: minutes,
  background: minutes === null ? false : step.background,
})

/** What the API reads: the whole list, in order, ingredients and utensils by name. */
export const stepsPayload = (steps: RecipeStepDraft[] | undefined) =>
  (steps ?? []).map((step) => ({
    text: step.text.trim(),
    durationMinutes: step.durationMinutes,
    background: step.durationMinutes !== null && step.background,
    ingredientIds: step.ingredients.map(refOf),
    utensils: step.utensils.map(nameOf),
    waitsFor: step.waitsFor,
  }))

/** The message of an API refusal (400) of the recipe, if that is what `error` is. */
export const refusalOf = (error: unknown): string | null => {
  const { status, body, message } = (error ?? {}) as { status?: number; body?: Record<string, unknown>; message?: string }
  if (status !== 400) return null
  const detail = body?.['hydra:description'] ?? body?.detail ?? body?.error ?? message
  return typeof detail === 'string' && detail !== '' ? detail : null
}
