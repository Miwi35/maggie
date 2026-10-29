import type { Workout, WorkoutFeeling, WorkoutType } from './workoutFormat'

/** What the form holds: strings, as typed. */
export interface WorkoutValues {
  type: WorkoutType | ''
  title: string
  /** `datetime-local`: `2026-10-20T07:10`, local time. */
  startedAt: string
  durationMinutes: string
  distanceKm: string
  kcal: string
  avgHeartRate: string
  feeling: WorkoutFeeling | ''
  notes: string
}

export type WorkoutErrors = Partial<Record<keyof WorkoutValues, string>>

export interface WorkoutPayload {
  type: WorkoutType
  title: string
  startedAt: string
  durationMinutes: number
  distanceMeters: number | null
  kcal: number | null
  avgHeartRate: number | null
  feeling: WorkoutFeeling | null
  notes: string | null
}

const pad = (n: number, width = 2): string => String(n).padStart(width, '0')

/** `2026-10-20T07:10` (local) → `2026-10-20T07:10:00+02:00`: the instant, with the offset of that day here. */
export function toOffsetIso(local: string): string {
  const date = new Date(local)
  const offset = -date.getTimezoneOffset()
  const sign = offset < 0 ? '-' : '+'
  const abs = Math.abs(offset)

  return `${local.slice(0, 16)}:00${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
}

/** An ISO instant → the `datetime-local` value it reads as here. */
export function toLocalInput(iso: string): string {
  const date = new Date(iso)

  return `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** « 5,4 » or « 5.4 » → 5400. NaN when it is not a number. */
export const kmToMeters = (km: string): number => Math.round(Number(km.trim().replace(',', '.')) * 1000)

const isBlank = (value: string): boolean => value.trim() === ''

const whole = (value: string): number => (/^-?\d+$/.test(value.trim()) ? Number(value.trim()) : Number.NaN)

const outside = (n: number, min: number, max: number): boolean => Number.isNaN(n) || n < min || n > max

export function emptyValues(now: Date = new Date()): WorkoutValues {
  return {
    type: '',
    title: '',
    startedAt: toLocalInput(now.toISOString()),
    durationMinutes: '',
    distanceKm: '',
    kcal: '',
    avgHeartRate: '',
    feeling: '',
    notes: '',
  }
}

export function valuesOf(workout: Workout): WorkoutValues {
  return {
    type: workout.type,
    title: workout.title,
    startedAt: toLocalInput(workout.startedAt),
    durationMinutes: String(workout.durationMinutes),
    distanceKm: workout.distanceMeters == null ? '' : String(workout.distanceMeters / 1000).replace('.', ','),
    kcal: workout.kcal == null ? '' : String(workout.kcal),
    avgHeartRate: workout.avgHeartRate == null ? '' : String(workout.avgHeartRate),
    feeling: workout.feeling ?? '',
    notes: workout.notes ?? '',
  }
}

/** The messages of the fields that do not hold; empty when the form can be sent. */
export function validate(values: WorkoutValues): WorkoutErrors {
  const errors: WorkoutErrors = {}
  if (values.type === '') errors.type = 'Choisissez le type de séance.'
  if (isBlank(values.title)) errors.title = 'Indiquez le titre de la séance.'
  else if (values.title.trim().length > 255) errors.title = 'Le titre ne peut pas dépasser 255 caractères.'
  if (isBlank(values.startedAt) || Number.isNaN(new Date(values.startedAt).getTime())) {
    errors.startedAt = 'Indiquez la date et l’heure de début.'
  }
  if (isBlank(values.durationMinutes)) errors.durationMinutes = 'Indiquez la durée en minutes.'
  else if (outside(whole(values.durationMinutes), 1, 1440)) errors.durationMinutes = 'La durée doit être comprise entre 1 et 1 440 minutes.'
  if (!isBlank(values.distanceKm) && outside(kmToMeters(values.distanceKm), 0, 1_000_000)) {
    errors.distanceKm = 'La distance doit être comprise entre 0 et 1 000 km.'
  }
  if (!isBlank(values.kcal) && outside(whole(values.kcal), 0, 20_000)) errors.kcal = 'Les kcal doivent être comprises entre 0 et 20 000.'
  if (!isBlank(values.avgHeartRate) && outside(whole(values.avgHeartRate), 20, 250)) {
    errors.avgHeartRate = 'La fréquence cardiaque doit être comprise entre 20 et 250 bpm.'
  }

  return errors
}

/** The body sent to `/api/workouts`: the values already validated. */
export function toPayload(values: WorkoutValues): WorkoutPayload {
  return {
    type: values.type as WorkoutType,
    title: values.title.trim(),
    startedAt: toOffsetIso(values.startedAt),
    durationMinutes: whole(values.durationMinutes),
    distanceMeters: isBlank(values.distanceKm) ? null : kmToMeters(values.distanceKm),
    kcal: isBlank(values.kcal) ? null : whole(values.kcal),
    avgHeartRate: isBlank(values.avgHeartRate) ? null : whole(values.avgHeartRate),
    feeling: values.feeling === '' ? null : values.feeling,
    notes: isBlank(values.notes) ? null : values.notes.trim(),
  }
}
