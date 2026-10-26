import { useMemo } from 'react'
import { endDateOf, lastDayOf } from '../../dates'
import { EventFormDialog, toLocalDatetime } from './EventFormDialog'
import type { EventFormValues } from './EventFormDialog'
import type { EventReminders } from './ReminderPicker'
import type { EventStatus } from './eventStatus'

export interface EditableEvent {
  summary: string
  /** An instant (ISO 8601), or for an all-day event its first date, `YYYY-MM-DD`. */
  start: string
  /** An instant (ISO 8601), or for an all-day event its end date, excluded as stored (MAG-382). */
  end: string
  allDay: boolean
  description?: string
  location?: string
  reminders?: EventReminders | null
  status?: EventStatus
  /** The agenda's IRI. */
  agenda?: string
  /** The rule of the series an occurrence belongs to: shown, not editable from the occurrence. */
  seriesRrule?: string | null
}

/**
 * One pair is set, the other is null: a PATCH that switches between the two kinds
 * has to clear the pair it leaves, or the API refuses it (MAG-382).
 */
export interface EventEditValues {
  summary: string
  startAt: string | null
  endAt: string | null
  /** `YYYY-MM-DD`, set on an all-day event only. */
  startDate: string | null
  /** `YYYY-MM-DD`, excluded as the API stores it, set on an all-day event only. */
  endDate: string | null
  allDay: boolean
  description: string | null
  location: string | null
  reminders: EventReminders | null
  status: EventStatus
  agenda: string
  /** Only sent when the owner made a one-off event repeat. */
  rrule?: string
}

interface EventEditDialogProps {
  open: boolean
  event: EditableEvent | null
  onClose: () => void
  onSubmit: (values: EventEditValues) => void
}

const BLANK_EVENT: EditableEvent = { summary: '', start: '', end: '', allDay: true }

const initialValues = (event: EditableEvent): EventFormValues => {
  let startAt: string
  let endAt: string
  if (event.allDay) {
    // The owner types and reads the last day included, as in Google Agenda; the
    // event carries the exclusive end. Dates both: no time zone to cross.
    startAt = event.start.slice(0, 10)
    endAt = event.end ? lastDayOf(event.end.slice(0, 10)) : startAt
  } else {
    startAt = toLocalDatetime(new Date(event.start))
    endAt = toLocalDatetime(new Date(event.end))
  }

  return {
    summary: event.summary,
    allDay: event.allDay,
    startAt,
    endAt,
    rrule: null,
    status: event.status ?? 'confirmed',
    reminders: event.reminders ?? null,
    agenda: event.agenda ?? '',
    location: event.location ?? '',
    description: event.description ?? '',
  }
}

export const EventEditDialog = ({ open, event, onClose, onSubmit }: EventEditDialogProps) => {
  const initial = useMemo(() => initialValues(event ?? BLANK_EVENT), [event])

  const handleSubmit = (values: EventFormValues) => {
    onSubmit({
      summary: values.summary,
      ...(values.allDay
        ? { startDate: values.startAt, endDate: endDateOf(values.endAt || values.startAt), startAt: null, endAt: null }
        : {
            startAt: new Date(values.startAt).toISOString(),
            endAt: new Date(values.endAt).toISOString(),
            startDate: null,
            endDate: null,
          }),
      allDay: values.allDay,
      description: values.description || null,
      location: values.location || null,
      reminders: values.reminders,
      status: values.status,
      agenda: values.agenda,
      ...(values.rrule ? { rrule: values.rrule } : {}),
    })
  }

  return (
    <EventFormDialog
      open={open}
      title="Modifier l'événement"
      submitLabel="Enregistrer"
      initial={initial}
      seriesRrule={event?.seriesRrule}
      onClose={onClose}
      onSubmit={handleSubmit}
    />
  )
}
