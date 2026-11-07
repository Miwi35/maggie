import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch } from './healthProfileApi'
import { DAILY_BALANCE_PATH } from './nutritionBalance'
import type { BalanceDay, DailyBalance } from './nutritionBalance'

/**
 * Santé › Nutrition (MAG-748): the day's balance (`GET /api/health/daily-balance`),
 * the food journal (`/api/health/food-intakes`, served by Doctrine: an event
 * may be read again at once) and its writes.
 */

export const FOOD_INTAKES_PATH = '/api/health/food-intakes'

/**
 * What changes the day: an intake (the journal, Maggie's), a meal (planned,
 * moved, its recipe). One array, so the page opens one stream (MAG-473).
 */
export const NUTRITION_TOPICS = ['/api/health/food-intakes/{id}', '/api/meals/{id}']

export type IntakeSource = 'meal' | 'manual' | 'conversation'

export const SOURCE_LABELS: Record<IntakeSource, string> = {
  meal: 'Repas',
  manual: 'Saisie',
  conversation: 'Maggie',
}

export interface FoodIntake {
  id: string
  date: string
  eatenAt: string
  mealId?: string
  ciqualCode?: string
  label: string
  quantityGrams?: number
  kcal: number
  proteinG: number
  carbsG: number
  fatG: number
  source: IntakeSource
}

/** A Ciqual food and its grams: the API computes the figures and the label. */
export interface CiqualIntake {
  eatenAt: string
  ciqualCode: string
  quantityGrams: number
}

/** A dish typed by hand: its label and kcal, macros if known. */
export interface FreeIntake {
  eatenAt: string
  label: string
  kcal: number
  proteinG?: number
  carbsG?: number
  fatG?: number
}

export type IntakePayload = CiqualIntake | FreeIntake | { mealId: string }

export type Loaded<T> = { status: 'loading' } | { status: 'error' } | { status: 'ready'; data: T }

/** A write's answer: ok, or the API's refusal (`detail` of the problem), or a failure without one. */
export type WriteResult = { ok: true } | { ok: false; detail: string | null }

const JSON_LD = { Accept: 'application/ld+json' }

/**
 * A read kept in step: `refresh` reads again, and only the last read asked for
 * writes (`useMonthBalance`). The previous answer stays on screen meanwhile.
 */
function useRead<T>(path: string, parse: (body: unknown) => T, headers: Record<string, string> = {}): Loaded<T> & { refresh: () => void } {
  const [state, setState] = useState<{ path: string; loaded: Loaded<T> } | null>(null)
  const latest = useRef(0)
  const parseRef = useRef(parse)
  const headersRef = useRef(headers)

  const refresh = useCallback(() => {
    const request = ++latest.current
    apiFetch(path, { headers: headersRef.current })
      .then(async (res) => {
        if (!res.ok) throw new Error(String(res.status))
        const data = parseRef.current(await res.json())
        if (request === latest.current) setState({ path, loaded: { status: 'ready', data } })
      })
      .catch(() => {
        if (request === latest.current) setState({ path, loaded: { status: 'error' } })
      })
  }, [path])

  useEffect(() => {
    refresh()

    return () => {
      // A read still on its way when the path changes or the page leaves writes nothing.
      latest.current += 1
    }
  }, [refresh])

  const loaded: Loaded<T> = state?.path === path ? state.loaded : { status: 'loading' }

  return { ...loaded, refresh }
}

const asBalance = (body: unknown): DailyBalance => {
  if (typeof body !== 'object' || body === null || !('eatenKcal' in body)) throw new Error('not a balance')
  const balance = body as DailyBalance

  return { ...balance, plannedMeals: balance.plannedMeals ?? [] }
}

const asDays = (body: unknown): BalanceDay[] => {
  const days = (body as { days?: unknown } | null)?.days
  if (!Array.isArray(days)) throw new Error('not a range')

  return days as BalanceDay[]
}

const asIntakes = (body: unknown): FoodIntake[] => {
  if (Array.isArray(body)) return body as FoodIntake[]
  const member = (body as { member?: unknown } | null)?.member
  if (!Array.isArray(member)) throw new Error('not a collection')

  return member as FoodIntake[]
}

/** The balance of one day, its planned meals not eaten yet included. */
export const useDailyBalance = (day: string) => useRead(`${DAILY_BALANCE_PATH}?date=${day}`, asBalance)

/** One row per day of `from`..`to`. */
export const useBalanceRange = (from: string, to: string) => useRead(`${DAILY_BALANCE_PATH}?from=${from}&to=${to}`, asDays)

/** The day's journal, in the order it was eaten. */
export const useFoodIntakes = (day: string) =>
  useRead(`${FOOD_INTAKES_PATH}?date[after]=${day}&date[before]=${day}`, asIntakes, JSON_LD)

const refusal = async (res: Response): Promise<string | null> => {
  try {
    const body = (await res.json()) as { detail?: unknown; description?: unknown }
    const detail = body.detail ?? body.description

    return typeof detail === 'string' && detail.trim() !== '' ? detail : null
  } catch {
    return null
  }
}

async function write(path: string, init: RequestInit): Promise<WriteResult> {
  try {
    const res = await apiFetch(path, init)
    if (res.ok) return { ok: true }

    return { ok: false, detail: res.status === 400 || res.status === 422 ? await refusal(res) : null }
  } catch {
    return { ok: false, detail: null }
  }
}

/** Records an intake: « Mangé » (`mealId` alone), a Ciqual food or a typed dish. */
export const createIntake = (payload: IntakePayload): Promise<WriteResult> =>
  write(FOOD_INTAKES_PATH, {
    method: 'POST',
    headers: { ...JSON_LD, 'Content-Type': 'application/ld+json' },
    body: JSON.stringify(payload),
  })

export const deleteIntake = (id: string): Promise<WriteResult> => write(`${FOOD_INTAKES_PATH}/${id}`, { method: 'DELETE' })
