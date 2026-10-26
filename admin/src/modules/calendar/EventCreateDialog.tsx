import { useMemo, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import { endDateOf, lastDayOf, localDay } from '../../dates'
import { EventFormDialog, toLocalDate, toLocalDatetime } from './EventFormDialog'
import type { EventFormValues } from './EventFormDialog'

interface EventCreateDialogProps {
  open: boolean
  onClose: () => void
  onCreated: () => void
  defaultStart?: Date
  defaultEnd?: Date
  defaultAllDay?: boolean
}

const initialValues = (defaultStart?: Date, defaultEnd?: Date, defaultAllDay?: boolean): EventFormValues => {
  const allDay = defaultAllDay ?? false
  let startAt = ''
  let endAt = ''
  if (defaultStart) {
    startAt = allDay ? toLocalDate(defaultStart) : toLocalDatetime(defaultStart)
  }
  if (defaultEnd) {
    // FullCalendar's all-day end is exclusive, like the API's; the field shows the last day
    endAt = allDay ? lastDayOf(localDay(defaultEnd)) : toLocalDatetime(defaultEnd)
  } else if (defaultStart) {
    endAt = allDay ? toLocalDate(defaultStart) : toLocalDatetime(new Date(defaultStart.getTime() + 3600_000))
  }

  return {
    summary: '',
    allDay,
    startAt,
    endAt,
    rrule: null,
    status: 'confirmed',
    reminders: null,
    agenda: '',
    location: '',
    description: '',
  }
}

export const EventCreateDialog = ({ open, onClose, onCreated, defaultStart, defaultEnd, defaultAllDay }: EventCreateDialogProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const [submitting, setSubmitting] = useState(false)

  const initial = useMemo(
    () => initialValues(defaultStart, defaultEnd, defaultAllDay),
    [defaultStart, defaultEnd, defaultAllDay],
  )

  const handleSubmit = (values: EventFormValues) => {
    setSubmitting(true)

    // A timed event is an instant: the browser's wall-clock time, made explicit.
    // An all-day event is a pair of dates, the end excluded as in Google, and nothing
    // else (MAG-382): the field holds the last day, the API wants the day after.
    const timing = values.allDay
      ? { allDay: true, startDate: values.startAt, endDate: endDateOf(values.endAt || values.startAt) }
      : { allDay: false, startAt: new Date(values.startAt).toISOString(), endAt: new Date(values.endAt).toISOString() }

    dataProvider
      .create('events', {
        data: {
          summary: values.summary,
          ...timing,
          agenda: values.agenda,
          status: values.status,
          ...(values.rrule ? { rrule: values.rrule } : {}),
          ...(values.reminders ? { reminders: values.reminders } : {}),
          ...(values.location ? { location: values.location } : {}),
          ...(values.description ? { description: values.description } : {}),
        },
      })
      .then(() => {
        notify('Événement créé', { type: 'success' })
        onCreated()
        onClose()
      })
      .catch((error: Error) => {
        notify(`Erreur: ${error.message}`, { type: 'error' })
      })
      .finally(() => setSubmitting(false))
  }

  return (
    <EventFormDialog
      open={open}
      title="Nouvel événement"
      submitLabel="Créer"
      initial={initial}
      submitting={submitting}
      onClose={onClose}
      onSubmit={handleSubmit}
    />
  )
}
