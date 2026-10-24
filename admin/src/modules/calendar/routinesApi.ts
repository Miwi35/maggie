import { ApiStatusError, apiUrl, headers } from './taskViews'

/** The moment a routine hangs on (MAG-511): one of the day's, or an hour of its own. */
export const ANCHORS = ['wake', 'afterBreakfast', 'afterLunch', 'afterDinner', 'time'] as const
export type Anchor = (typeof ANCHORS)[number]

export const RECURRENCE_KINDS = ['daily', 'weekdays', 'weekly', 'monthly'] as const
export type RecurrenceKind = (typeof RECURRENCE_KINDS)[number]

/** `weekday` 1-7 (Monday is 1) for `weekly`, `monthDay` 1-31 for `monthly`. */
export interface Recurrence {
  kind: RecurrenceKind
  weekday?: number
  monthDay?: number
}

export interface RecentDay {
  date: string
  due: boolean
  checked: boolean
}

/** `GET /api/routines`: a routine, and what the API counts of it. `time` is the resolved hour, `HH:MM`. */
export interface Routine {
  id: string
  title: string
  anchor: Anchor
  anchorTime: string | null
  time: string
  recurrence: Recurrence
  active: boolean
  remind: boolean
  createdAt: string
  checkedToday: boolean
  dueToday: boolean
  monthCount: number
  totalCount: number
  /** Seven days, the oldest first, today last. */
  recentDays: RecentDay[]
}

/** What the form writes: a creation sends it whole, a change only the fields it moved. */
export interface RoutineFields {
  title: string
  anchor: Anchor
  anchorTime?: string | null
  recurrence: Recurrence
  active?: boolean
  remind?: boolean
}

const routineError = async (response: Response): Promise<Error> => {
  const body = (await response.json().catch(() => ({}))) as { error?: string }

  return new ApiStatusError(body.error ?? `Routines : ${response.status}`, response.status)
}

const JSON_HEADERS = { Accept: 'application/json', 'Content-Type': 'application/json' }

async function send(url: string, init: RequestInit): Promise<Routine> {
  const response = await fetch(url, init)
  if (!response.ok) throw await routineError(response)

  return (await response.json()) as Routine
}

/** Every routine, active or not, by hour then title. */
export async function fetchRoutines(): Promise<Routine[]> {
  const response = await fetch(`${apiUrl}/routines`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) throw await routineError(response)
  const body = (await response.json()) as { member?: Routine[] }

  return body.member ?? []
}

export function createRoutine(fields: RoutineFields): Promise<Routine> {
  return send(`${apiUrl}/routines`, { method: 'POST', headers: headers(JSON_HEADERS), body: JSON.stringify(fields) })
}

/** Only the fields present change. */
export function updateRoutine(id: string, fields: Partial<RoutineFields>): Promise<Routine> {
  return send(`${apiUrl}/routines/${id}`, { method: 'PATCH', headers: headers(JSON_HEADERS), body: JSON.stringify(fields) })
}

/** Removes the routine and its ticks. */
export async function deleteRoutine(id: string): Promise<void> {
  const response = await fetch(`${apiUrl}/routines/${id}`, { method: 'DELETE', headers: headers() })
  if (!response.ok) throw await routineError(response)
}

/** Ticks (`checked`) or unticks the routine on `date` (`YYYY-MM-DD`); both idempotent. */
export function checkRoutine(id: string, date: string, checked: boolean): Promise<Routine> {
  return send(`${apiUrl}/routines/${id}/checks/${date}`, {
    method: checked ? 'PUT' : 'DELETE',
    headers: headers({ Accept: 'application/json' }),
  })
}

/** A routine due on a day, as `GET /api/routines/day/{date}` sends it (MAG-512). */
export type DayRoutine = Pick<Routine, 'id' | 'title' | 'anchor' | 'anchorTime' | 'time' | 'remind' | 'recurrence'> & {
  checked: boolean
}

/** The routines due at one hour of the day; moments come in the order of the day. */
export interface DayMoment {
  anchor: Anchor
  time: string
  routines: DayRoutine[]
}

export interface RoutinesDay {
  date: string
  moments: DayMoment[]
}

/** The routines due on `date` (`YYYY-MM-DD`), grouped by moment. */
export async function fetchRoutinesDay(date: string): Promise<RoutinesDay> {
  const response = await fetch(`${apiUrl}/routines/day/${date}`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) throw await routineError(response)
  const body = (await response.json()) as Partial<RoutinesDay>

  return { date: body.date ?? date, moments: body.moments ?? [] }
}

// A tick is published on its own topic, apart from the routine it counts for.
export const ROUTINE_TOPICS = ['/api/routines/{id}', '/api/routine_checks/{id}']

/** The part of the day an hour falls in, as the reminders say it: « matin », « après-midi », « soir ». */
export function dayPartOf(time: string): 'matin' | 'après-midi' | 'soir' {
  if (time < '12:00') return 'matin'
  if (time < '18:00') return 'après-midi'

  return 'soir'
}

/**
 * What the « Aujourd'hui » band shows at `now` (`HH:MM`): the routines of the current moment — the last whose
 * hour has passed, the first before the day starts — and those still to tick of the earlier ones. Null when
 * there is nothing to show.
 */
export function bandAt(day: RoutinesDay, now: string): { part: string; routines: DayRoutine[] } | null {
  const passed = day.moments.filter((moment) => moment.time <= now).length
  const index = Math.max(passed - 1, 0)
  const current = day.moments[index]
  if (!current) return null

  const routines = day.moments.flatMap((moment, i) => (i < index ? moment.routines.filter((r) => !r.checked) : i === index ? moment.routines : []))

  return routines.length > 0 ? { part: dayPartOf(current.time), routines } : null
}

export type RoutineGroup = 'morning' | 'afternoon' | 'evening' | 'weekly' | 'monthly'

export const GROUPS: { key: RoutineGroup; label: string }[] = [
  { key: 'morning', label: 'Matin' },
  { key: 'afternoon', label: 'Après-midi' },
  { key: 'evening', label: 'Soir' },
  { key: 'weekly', label: 'Chaque semaine' },
  { key: 'monthly', label: 'Chaque mois' },
]

/** The group a routine is listed in: its rhythm when it is not every day, else its hour. */
export function groupOf(routine: Routine): RoutineGroup {
  if (routine.recurrence.kind === 'weekly') return 'weekly'
  if (routine.recurrence.kind === 'monthly') return 'monthly'
  if (routine.time < '12:00') return 'morning'
  if (routine.time < '18:00') return 'afternoon'

  return 'evening'
}

export const WEEKDAYS = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche']

const MOMENTS: Record<Exclude<Anchor, 'time'>, string> = {
  wake: 'au réveil',
  afterBreakfast: 'après le petit-déjeuner',
  afterLunch: 'après le déjeuner',
  afterDinner: 'après le dîner',
}

/** « Au réveil · 7 h », « 22 h 30 » for a moment of its own hour. */
export function momentLabel(moment: Pick<DayMoment, 'anchor' | 'time'>): string {
  if (moment.anchor === 'time') return hourLabel(moment.time)
  const name = MOMENTS[moment.anchor]

  return `${name.charAt(0).toUpperCase()}${name.slice(1)} · ${hourLabel(moment.time)}`
}

/** « 22 h 30 », « 7 h ». */
export function hourLabel(time: string): string {
  const [hours, minutes] = time.split(':')
  const h = String(Number(hours))

  return minutes === '00' ? `${h} h` : `${h} h ${minutes}`
}

/** When it comes: « au réveil », « à 22 h 30 », « le mardi, après le dîner », « le 15, au réveil ». */
export function whenLabel(routine: Pick<Routine, 'anchor' | 'anchorTime' | 'time' | 'recurrence'>): string {
  const moment =
    routine.anchor === 'time' ? `à ${hourLabel(routine.anchorTime ?? routine.time)}` : MOMENTS[routine.anchor]
  const { kind, weekday, monthDay } = routine.recurrence
  if (kind === 'weekly' && weekday) return `le ${WEEKDAYS[weekday - 1]}, ${moment}`
  if (kind === 'monthly' && monthDay) return `le ${monthDay === 1 ? '1er' : monthDay}, ${moment}`
  if (kind === 'weekdays') return `en semaine, ${moment}`

  return moment
}

const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre']

const times = (n: number): string => `${n} fois`

/** « ce mois : 13 fois » for a daily rhythm, « depuis août : 9 fois » for a weekly or monthly one. */
export function countLabel(routine: Pick<Routine, 'recurrence' | 'monthCount' | 'totalCount' | 'createdAt'>): string {
  if (routine.recurrence.kind === 'weekly' || routine.recurrence.kind === 'monthly') {
    // The month the routine was created in, as its own timestamp writes it.
    const month = Number(routine.createdAt.slice(5, 7))

    return `depuis ${MONTHS[month - 1]} : ${times(routine.totalCount)}`
  }

  return `ce mois : ${times(routine.monthCount)}`
}
