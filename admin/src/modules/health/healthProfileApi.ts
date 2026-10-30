import { useCallback, useEffect, useRef, useState } from 'react'
import { useNotify } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'

/**
 * Santé › Configuration (MAG-707): the health profile read from and written to
 * `GET|PATCH /api/health/profile` (the phone and Maggie read the same). A PATCH
 * carries only the field changed; the answer is the whole profile, and so is
 * every Mercure payload: it replaces the local one, it is never merged.
 */

export const HEALTH_PROFILE_PATH = '/api/health/profile'

/**
 * The item topic as a template: the profile has no id until its first write,
 * and the template also receives the event of that creation. One array, so the
 * page opens one stream (MAG-473).
 */
export const HEALTH_PROFILE_TOPICS = ['/api/health-profile/{id}']

export const SEXES = ['male', 'female'] as const
export type Sex = (typeof SEXES)[number]

export const ACTIVITY_LEVELS = ['sedentary', 'light', 'moderate', 'active', 'very_active'] as const
export type ActivityLevel = (typeof ACTIVITY_LEVELS)[number]

export const SESSION_MINUTES = [15, 20, 30] as const
export const STEP_SOURCES = ['health_connect', 'none'] as const
export type StepSource = (typeof STEP_SOURCES)[number]
export type NeedMode = 'computed' | 'entered'

export interface HealthProfile {
  id: string | null
  sex: Sex | null
  birthYear: number | null
  age: number | null
  heightCm: number | null
  weightKg: number | null
  activityLevel: ActivityLevel
  mode: NeedMode
  enteredNeedKcal: number | null
  bmrKcal: number | null
  needKcal: number | null
  factor: number | null
  stepGoal: number
  suggestionWindowStart: string
  suggestionWindowEnd: string
  sessionMinutes: number
  stepSource: StepSource
}

/** What a PATCH may carry: the fields the API writes, one at a time from this page. */
export type HealthProfilePatch = Partial<
  Pick<
    HealthProfile,
    | 'sex'
    | 'birthYear'
    | 'heightCm'
    | 'weightKg'
    | 'activityLevel'
    | 'mode'
    | 'enteredNeedKcal'
    | 'stepGoal'
    | 'suggestionWindowStart'
    | 'suggestionWindowEnd'
    | 'sessionMinutes'
    | 'stepSource'
  >
>

export const ACTIVITY_LABELS: Record<ActivityLevel, string> = {
  sedentary: 'Sédentaire',
  light: 'Légère',
  moderate: 'Modérée',
  active: 'Active',
  very_active: 'Très active',
}

export const ACTIVITY_FACTORS: Record<ActivityLevel, number> = {
  sedentary: 1.2,
  light: 1.375,
  moderate: 1.55,
  active: 1.725,
  very_active: 1.9,
}

/** The bounds of the entered need (the API's rule): a whole number of kcal, by steps of 50. */
export const ENTERED_NEED_MIN = 1000
export const ENTERED_NEED_MAX = 5000
export const ENTERED_NEED_STEP = 50
const ENTERED_NEED_FALLBACK = 2000

export const LOAD_ERROR = 'Erreur : le profil n’a pas pu être chargé.'
export const SAVE_ERROR = 'Erreur : le profil n’a pas pu être enregistré.'
export const REFUSED_ERROR = 'Valeur refusée : rien n’a été enregistré.'

/**
 * The need the switch to « Saisi » starts from: the one shown, to the nearest
 * step of 50, within the bounds; 2 000 without one.
 */
export const enteredNeedFrom = (needKcal: number | null): number => {
  if (needKcal === null) return ENTERED_NEED_FALLBACK
  const rounded = Math.round(needKcal / ENTERED_NEED_STEP) * ENTERED_NEED_STEP

  return Math.min(ENTERED_NEED_MAX, Math.max(ENTERED_NEED_MIN, rounded))
}

const INTEGER = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 })
const DECIMALS = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 })
const ONE_DECIMAL = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 })

/** « 1 980 » (fine non-breaking space, as the other Santé pages). */
export const formatInteger = (value: number): string => INTEGER.format(value)
/** « 72,4 », « 72 ». */
export const formatKg = (value: number): string => ONE_DECIMAL.format(value)
/** « × 1,2 », « × 1,24 »: the useless decimals dropped, as the admin mockup. */
export const formatFactor = (value: number): string => `× ${DECIMALS.format(value)}`
/** « 1 980 kcal », « — kcal ». */
export const formatKcal = (value: number | null): string => `${value === null ? '—' : formatInteger(value)} kcal`

/** A typed figure: spaces of any kind dropped, a decimal comma read as a point; null when it is not one. */
export const parseFigure = (typed: string): number | null => {
  const cleaned = typed.replace(/\s/g, '').replace(',', '.')
  if (cleaned === '' || !/^\d+(\.\d+)?$/.test(cleaned)) return null

  return Number(cleaned)
}

export function apiFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')

  return fetch(path, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}

/** The profile as a payload gives it: the published one carries an `@id` the page does not keep. */
const profileOf = (data: Record<string, unknown>): HealthProfile => {
  const profile: Record<string, unknown> = { ...data }
  for (const key of ['@id', '@context', '@type']) delete profile[key]

  return profile as unknown as HealthProfile
}

/**
 * The profile, loaded and kept in step with Mercure; `save` shows a change at
 * once, sends it, and falls back to the server's profile when it is refused.
 * Saves go one after the other, so the last change is the one that stays.
 */
export function useHealthProfile() {
  const notify = useNotify()
  const [profile, setProfile] = useState<HealthProfile | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const server = useRef<HealthProfile | null>(null)
  const queue = useRef<Promise<unknown>>(Promise.resolve())
  // Saves sent and not answered: an event meanwhile must not undo what is on its way.
  const pending = useRef(0)

  useEffect(() => {
    let cancelled = false
    apiFetch(HEALTH_PROFILE_PATH)
      .then(async (res) => {
        if (!res.ok) throw new Error(String(res.status))
        const data = profileOf(await res.json())
        if (cancelled) return
        server.current = data
        if (pending.current === 0) setProfile(data)
      })
      .catch(() => {
        if (cancelled) return
        setLoadFailed(true)
        notify(LOAD_ERROR, { type: 'error' })
      })

    return () => {
      cancelled = true
    }
  }, [notify])

  // A change made elsewhere (the phone, Maggie, a weighing): the payload is the whole profile.
  const handleMercure = useCallback((data?: string) => {
    if (!data) return
    try {
      const next = profileOf(JSON.parse(data) as Record<string, unknown>)
      server.current = next
      if (pending.current === 0) setProfile(next)
    } catch {
      // Not a profile: nothing to apply.
    }
  }, [])

  useMercure(HEALTH_PROFILE_TOPICS, handleMercure)

  const save = useCallback(
    (patch: HealthProfilePatch, shown: Partial<HealthProfile> = patch): Promise<boolean> => {
      setProfile((current) => (current ? { ...current, ...shown } : current))
      pending.current += 1

      const send = async (): Promise<boolean> => {
        try {
          const res = await apiFetch(HEALTH_PROFILE_PATH, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(patch),
          })
          if (res.ok) {
            const data = profileOf(await res.json())
            server.current = data
            pending.current -= 1
            if (pending.current === 0) setProfile(data)

            return true
          }
          notify(res.status === 400 ? REFUSED_ERROR : SAVE_ERROR, { type: 'error' })
        } catch {
          notify(SAVE_ERROR, { type: 'error' })
        }
        pending.current -= 1
        setProfile(server.current)

        return false
      }

      const run = queue.current.then(send)
      queue.current = run

      return run
    },
    [notify],
  )

  return { profile, loadFailed, save }
}

/**
 * The day's need alone, for the « % du besoin du jour » of a recipe sheet:
 * null while it is read, when the profile cannot give one, or on a failure —
 * the line is then left out, never guessed.
 */
export function useNeedKcal(): number | null {
  const [need, setNeed] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    apiFetch(HEALTH_PROFILE_PATH)
      .then(async (res) => {
        if (!res.ok) return
        const data = (await res.json()) as Partial<HealthProfile>
        if (!cancelled) setNeed(typeof data.needKcal === 'number' ? data.needKcal : null)
      })
      .catch(() => undefined)

    return () => {
      cancelled = true
    }
  }, [])

  return need
}
