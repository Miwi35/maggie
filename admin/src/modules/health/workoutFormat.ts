/**
 * How a workout is spelled on screen (MAG-705): the labels and formats of the
 * plan's « Libellés » table, identical on Android.
 */

export const WORKOUT_TYPES = ['run', 'walk', 'bike', 'swim', 'strength', 'climbing', 'other'] as const
export const WORKOUT_FEELINGS = ['very_good', 'good', 'hard', 'very_hard'] as const
export const WORKOUT_SOURCES = ['manual', 'watch', 'activity', 'estimated', 'planned'] as const

export type WorkoutType = (typeof WORKOUT_TYPES)[number]
export type WorkoutFeeling = (typeof WORKOUT_FEELINGS)[number]
export type WorkoutSource = (typeof WORKOUT_SOURCES)[number]

/** A workout as `/api/workouts` gives it; `id` is the IRI the Hydra provider keys the record by. */
export interface Workout {
  id: string
  type: WorkoutType
  title: string
  startedAt: string
  durationMinutes: number
  distanceMeters?: number | null
  kcal?: number | null
  avgHeartRate?: number | null
  feeling?: WorkoutFeeling | null
  notes?: string | null
  source: WorkoutSource
  externalId?: string | null
  difficulty?: number | null
  variant?: WorkoutVariant | null
  exercises?: WorkoutExercise[]
}

export type WorkoutVariant = 'normal' | 'calibration' | 'soft' | 'deload'
export type ExerciseUnit = 'reps' | 'seconds' | 'meters'
export type ExerciseFeeling = 'too_easy' | 'right' | 'too_hard'

/** A set as it was done (MAG-775): the count in the exercise's unit, and the load if any. */
export interface WorkoutSet {
  reps?: number
  seconds?: number
  meters?: number
  loadKg?: number
}

/** A prescribed exercise of the session, in its order; `name` is the catalogue's French name. */
export interface WorkoutExercise {
  exercise: string
  name: string
  unit: ExerciseUnit
  step: number
  sets: number
  target: number
  targetMin: number
  targetMax: number
  loadKg: number | null
  restSeconds: number
  tempo: boolean
  targetRir: number | null
  cue: string | null
  feeling: ExerciseFeeling | null
  done: WorkoutSet[]
}

export const TYPE_LABELS: Record<WorkoutType, string> = {
  run: 'Course à pied',
  walk: 'Marche',
  bike: 'Vélo',
  swim: 'Natation',
  strength: 'Musculation',
  climbing: 'Escalade',
  other: 'Autre',
}

export const FEELING_LABELS: Record<WorkoutFeeling, string> = {
  very_good: 'Très bien',
  good: 'Bien',
  hard: 'Dur',
  very_hard: 'Très dur',
}

export const SOURCE_LABELS: Record<WorkoutSource, string> = {
  manual: 'Saisie',
  watch: 'Montre',
  activity: 'Activité Sport',
  estimated: 'Estimée',
  planned: 'Prévue',
}

export const typeLabel = (type: string): string => TYPE_LABELS[type as WorkoutType] ?? type
export const feelingLabel = (feeling: string): string => FEELING_LABELS[feeling as WorkoutFeeling] ?? feeling
export const sourceLabel = (source: string): string => SOURCE_LABELS[source as WorkoutSource] ?? source

/** « 45 min », « 1 h 05 ». */
export function formatDuration(minutes: number): string {
  if (minutes < 60) return `${minutes} min`

  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')}`
}

/** « 5,4 km », « 22,0 km »: metres in, one decimal, a comma. */
export function formatDistance(meters: number): string {
  return `${(meters / 1000).toFixed(1).replace('.', ',')} km`
}

const MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.']
const WEEKDAYS = ['Dim.', 'Lun.', 'Mar.', 'Mer.', 'Jeu.', 'Ven.', 'Sam.']

/** « 20 oct. », « 1er sept. »: the day in local time. */
export function formatShortDate(date: Date): string {
  const day = date.getDate()

  return `${day === 1 ? '1er' : day} ${MONTHS[date.getMonth()]}`
}

/** « Lun. 20 oct. ». */
export function formatLongDate(date: Date): string {
  return `${WEEKDAYS[date.getDay()]} ${formatShortDate(date)}`
}

const UNIT_SUFFIXES: Record<ExerciseUnit, string> = { reps: '', seconds: ' s', meters: ' m' }

/** « 12,5 », « 10 »: kilograms in French decimals, a whole number without any. */
const formatKg = (kg: number): string => `${String(kg).replace('.', ',')} kg`

/**
 * An exercise's dose, the same rule as Android (MAG-775): what is prescribed
 * (« 3 × 10 · 10 kg », « 3 × 30 s ») until sets are done, then what was done —
 * « 3 × 9 · 10 kg » when every set counts the same, « 8 · 8 · 7 » otherwise.
 */
export function formatDose(exercise: WorkoutExercise): string {
  const suffix = UNIT_SUFFIXES[exercise.unit] ?? ''
  if (exercise.done.length === 0) {
    const load = exercise.loadKg == null ? '' : ` · ${formatKg(exercise.loadKg)}`

    return `${exercise.sets} × ${exercise.target}${suffix}${load}`
  }

  const counts = exercise.done.map((set) => set[exercise.unit] ?? 0)
  if (counts.some((count) => count !== counts[0])) return counts.map((count) => `${count}${suffix}`).join(' · ')

  const loads = exercise.done.map((set) => set.loadKg)
  const shared = loads[0] != null && loads.every((load) => load === loads[0]) ? loads[0] : exercise.loadKg
  const load = shared == null ? '' : ` · ${formatKg(shared)}`

  return `${counts.length} × ${counts[0]}${suffix}${load}`
}

export const EXERCISE_FEELING_LABELS: Record<ExerciseFeeling, string> = {
  too_easy: 'Trop facile',
  right: 'Juste bien',
  too_hard: 'Trop dur',
}

export const exerciseFeelingLabel = (feeling: string): string => EXERCISE_FEELING_LABELS[feeling as ExerciseFeeling] ?? feeling

/** The ULID at the end of an IRI (`/api/workouts/01J…`): what a test id or a payload carries. */
export const workoutUlid = (id: string): string => id.slice(id.lastIndexOf('/') + 1)
