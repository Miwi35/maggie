import { addDays, endDateOf, lastDayOf } from '../../dates'
import { rruleToFrenchText } from './recurrenceUtils'

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

// One stream for the periods page and the week's bands (MAG-473): a period, or the agenda whose colour it wears.
export const PERIODS_TOPICS = ['/api/periods/{id}', '/api/agendas/{id}']

export const PERIODS_PATH = '/periods'

/** A period as the API serves it; the end is excluded, as an event's (MAG-382). */
export interface Period {
  /** The IRI from react-admin's provider, the bare ULID from a raw read. */
  id: string
  name: string
  /** The agenda's IRI. */
  agenda: string
  allDay: boolean
  startAt?: string | null
  endAt?: string | null
  startDate?: string | null
  endDate?: string | null
  rrule?: string | null
}

export const periodIri = (id: string): string => (id.startsWith('/') ? id : `/api/periods/${id}`)

/** The periods touching `[start, end)`, series unfolded: each occurrence has its own dates and the series' id. */
export async function fetchPeriods(start: string, end: string, signal?: AbortSignal): Promise<Period[]> {
  const token = localStorage.getItem('token')
  const query = new URLSearchParams({ expand: 'true', 'endAt[after]': start, 'startAt[before]': end })
  const response = await fetch(`${entrypoint}/periods?${query}`, {
    headers: { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    signal,
  })
  if (!response.ok) throw new Error(`periods ${response.status}`)
  const body = await response.json()

  return (body.member ?? body['hydra:member'] ?? []) as Period[]
}

const pad = (n: number) => String(n).padStart(2, '0')

const localDatetime = (d: Date) =>
  `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`

/** « ven. 23/10 18:00 » for a timed period, « 18/10/2026 » for a whole day. */
export function formatMoment(period: Period, field: 'start' | 'end'): string {
  if (period.allDay) {
    const day = field === 'start' ? period.startDate : period.endDate ? lastDayOf(period.endDate) : null
    if (!day) return ''
    const [y, m, d] = day.split('-')

    return `${d}/${m}/${y}`
  }
  const iso = field === 'start' ? period.startAt : period.endAt
  if (!iso) return ''
  const date = new Date(iso)
  const weekday = new Intl.DateTimeFormat('fr-FR', { weekday: 'short' }).format(date)

  return `${weekday} ${pad(date.getDate())}/${pad(date.getMonth() + 1)} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** The repeat choices of the dialog; `value` is the RRULE the API stores, empty for none. */
export const REPEAT_PRESETS = [
  { value: 'FREQ=WEEKLY;INTERVAL=2', label: 'Toutes les 2 semaines' },
  { value: '', label: 'Jamais' },
  { value: 'FREQ=WEEKLY', label: 'Chaque semaine' },
  { value: 'FREQ=YEARLY', label: 'Chaque année' },
]

// A rule without BYDAY reads the weekday of today in `rruleToFrenchText`: the presets are named as they are chosen.
export const formatRepeat = (rrule?: string | null): string =>
  REPEAT_PRESETS.find((preset) => preset.value === (rrule ?? ''))?.label ?? rruleToFrenchText(rrule ?? '')

/** What the form's inputs hold: the end day is the last one included, as the owner reads it. */
export interface PeriodFormValues {
  name: string
  agenda: string
  allDay: boolean
  /** `YYYY-MM-DD` when all-day, else `YYYY-MM-DDTHH:mm`. */
  startAt: string
  endAt: string
  rrule: string | null
}

export function toFormValues(period: Period | null, today: Date = new Date()): PeriodFormValues {
  if (!period) {
    const day = localDatetime(today).slice(0, 10)

    return { name: '', agenda: '', allDay: true, startAt: day, endAt: day, rrule: null }
  }
  const allDay = period.allDay

  return {
    name: period.name,
    agenda: period.agenda ?? '',
    allDay,
    startAt: allDay ? (period.startDate ?? '') : localDatetime(new Date(period.startAt ?? '')),
    endAt: allDay
      ? period.endDate && period.startDate
        ? lastDayOf(period.endDate)
        : (period.startDate ?? '')
      : localDatetime(new Date(period.endAt ?? '')),
    rrule: period.rrule ?? null,
  }
}

/** The body the API takes: instants or days, never both, the day end excluded. */
export function toPayload(values: PeriodFormValues): Record<string, unknown> {
  const schedule = values.allDay
    ? { startDate: values.startAt, endDate: endDateOf(values.endAt || values.startAt), startAt: null, endAt: null }
    : {
        startAt: new Date(values.startAt).toISOString(),
        endAt: new Date(values.endAt).toISOString(),
        startDate: null,
        endDate: null,
      }

  return { name: values.name, agenda: values.agenda, allDay: values.allDay, rrule: values.rrule, ...schedule }
}

/** A period's days within a week starting at `weekStart` (`YYYY-MM-DD`): 1-based first column and how many columns it spans. */
export function weekSpan(period: Period, weekStart: string): { column: number; span: number } | null {
  const first = period.allDay ? period.startDate : period.startAt ? localDatetime(new Date(period.startAt)).slice(0, 10) : null
  if (!first) return null
  let last: string
  if (period.allDay) {
    last = period.endDate ? lastDayOf(period.endDate) : first
  } else {
    const end = period.endAt ? new Date(period.endAt) : null
    // A timed end at midnight sharp belongs to the day before.
    last = end ? localDatetime(new Date(end.getTime() - (end.getHours() + end.getMinutes() === 0 ? 60_000 : 0))).slice(0, 10) : first
  }
  if (last < first) last = first
  const weekEnd = addDays(weekStart, 6)
  if (last < weekStart || first > weekEnd) return null
  const from = first < weekStart ? weekStart : first
  const to = last > weekEnd ? weekEnd : last
  const column = Math.round((Date.parse(`${from}T00:00:00Z`) - Date.parse(`${weekStart}T00:00:00Z`)) / 86_400_000) + 1
  const span = Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1

  return { column, span }
}

/** « jusqu'au ven. 18:00 » on a timed period, « jusqu'au ven. 18/10 » on a whole-day one. */
export function untilLabel(period: Period): string {
  if (period.allDay) {
    if (!period.endDate) return ''
    const last = lastDayOf(period.endDate)
    const date = new Date(`${last}T12:00:00`)
    const weekday = new Intl.DateTimeFormat('fr-FR', { weekday: 'short' }).format(date)

    return `jusqu'au ${weekday} ${last.slice(8, 10)}/${last.slice(5, 7)}`
  }
  if (!period.endAt) return ''
  const end = new Date(period.endAt)
  const weekday = new Intl.DateTimeFormat('fr-FR', { weekday: 'short' }).format(end)

  return `jusqu'au ${weekday} ${pad(end.getHours())}:${pad(end.getMinutes())}`
}
