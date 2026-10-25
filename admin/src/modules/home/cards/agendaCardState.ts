import { lastDayOf } from '../../../dates'
import type { AgendaHomeEvent, AgendaHomeState } from './agendaTimeline'

/** An event of `GET /api/events?expand=true`: a series already unfolded into its occurrences of the period. */
export interface ApiEvent {
  id: string
  summary: string
  description?: string | null
  location?: string | null
  allDay?: boolean
  startAt?: string | null
  endAt?: string | null
  startDate?: string | null
  endDate?: string | null
  timeZone?: string | null
  status?: string
  reminders?: AgendaHomeEvent['reminders']
  activity?: string | null
  /** The IRI of its agenda, `/api/agendas/{id}`. */
  agenda: string
}

/** An agenda of `GET /api/agendas`. */
export interface ApiAgenda {
  id: string
  name: string
  color?: string | null
}

const idOf = (iri: string): string => iri.slice(iri.lastIndexOf('/') + 1)

/** « Cabinet Martin, 15 rue de la Paix » → « Cabinet Martin »: what the row has room for. */
export const shortLocation = (location: string | null | undefined): string | null => {
  const first = location?.split(',')[0].trim()

  return first ? first : null
}

const startMs = (event: AgendaHomeEvent): number => (event.startAt ? Date.parse(event.startAt) : 0)

/** Whole-day events first, then by start, then by name. */
const byDay = (a: AgendaHomeEvent, b: AgendaHomeEvent): number =>
  Number(b.allDay) - Number(a.allDay) || startMs(a) - startMs(b) || a.summary.localeCompare(b.summary, 'fr')

/**
 * The card's state from the two resources it reads. `nowMs` is the device's clock, `null` on tomorrow,
 * which has no « maintenant » line.
 */
export function composeAgendaState(
  date: string,
  nowMs: number | null,
  events: ApiEvent[],
  agendas: ApiAgenda[],
): AgendaHomeState {
  const agendaById = new Map(agendas.map((agenda) => [agenda.id, agenda]))

  const shown = events
    .filter((event) => event.status !== 'cancelled')
    .map((event): AgendaHomeEvent => {
      const allDay = event.allDay === true
      const agendaId = idOf(event.agenda)
      const agenda = agendaById.get(agendaId)

      return {
        id: event.id,
        summary: event.summary,
        description: event.description ?? null,
        location: event.location ?? null,
        locationShort: shortLocation(event.location),
        allDay,
        ...(allDay
          ? { startDate: event.startDate ?? null, endDate: event.endDate ?? null, lastDay: event.endDate ? lastDayOf(event.endDate) : null }
          : { startAt: event.startAt ?? null, endAt: event.endAt ?? null }),
        timeZone: event.timeZone ?? null,
        status: event.status ?? 'confirmed',
        reminders: event.reminders ?? null,
        activity: event.activity ?? null,
        agendaId,
        agendaName: agenda?.name ?? '',
        agendaColor: agenda?.color ?? null,
      }
    })
    .sort(byDay)

  return { date, now: nowMs === null ? null : new Date(nowMs).toISOString(), events: shown }
}
