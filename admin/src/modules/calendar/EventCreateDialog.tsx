import { useEffect, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import { endDateOf, lastDayOf, localDay, parseDay } from '../../dates'
import { RecurrencePicker } from './RecurrencePicker'
import { ReminderPicker } from './ReminderPicker'
import type { EventReminders } from './ReminderPicker'
import type { EventStatus } from './eventStatus'

interface Calendar {
  id: string
  name: string
  default: boolean
}

interface EventCreateDialogProps {
  open: boolean
  onClose: () => void
  onCreated: () => void
  defaultStart?: Date
  defaultEnd?: Date
  defaultAllDay?: boolean
}

const toLocalDatetime = (d: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const toLocalDate = (d: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export const EventCreateDialog = ({ open, onClose, onCreated, defaultStart, defaultEnd, defaultAllDay }: EventCreateDialogProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [calendars, setCalendars] = useState<Calendar[]>([])
  const [summary, setSummary] = useState('')
  const [startAt, setStartAt] = useState('')
  const [endAt, setEndAt] = useState('')
  const [allDay, setAllDay] = useState(false)
  const [calendarId, setCalendarId] = useState('')
  const [rrule, setRrule] = useState<string | null>(null)
  const [reminders, setReminders] = useState<EventReminders | null>(null)
  const [status, setStatus] = useState<EventStatus>('confirmed')
  const [summaryError, setSummaryError] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  // Load calendars
  useEffect(() => {
    if (!open) return
    dataProvider
      .getList('agendas', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'name', order: 'ASC' },
        filter: {},
      })
      .then(({ data }) => {
        const cals = data as unknown as Calendar[]
        setCalendars(cals)
        const defaultCal = cals.find((c) => c.default) ?? cals[0]
        if (defaultCal && !calendarId) {
          setCalendarId(defaultCal.id)
        }
      })
      .catch(console.error)
  }, [open, dataProvider]) // eslint-disable-line react-hooks/exhaustive-deps

  // Pre-fill dates when dialog opens
  useEffect(() => {
    if (!open) return
    const isAllDay = defaultAllDay ?? false
    setAllDay(isAllDay)
    if (defaultStart) {
      setStartAt(isAllDay ? toLocalDate(defaultStart) : toLocalDatetime(defaultStart))
    } else {
      setStartAt('')
    }
    if (defaultEnd) {
      if (isAllDay) {
        // FullCalendar's all-day end is exclusive, like the API's; the field shows the last day
        setEndAt(lastDayOf(localDay(defaultEnd)))
      } else {
        setEndAt(toLocalDatetime(defaultEnd))
      }
    } else if (defaultStart) {
      setEndAt(isAllDay ? toLocalDate(defaultStart) : toLocalDatetime(new Date(defaultStart.getTime() + 3600_000)))
    } else {
      setEndAt('')
    }
    setSummary('')
    setRrule(null)
    setReminders(null)
    setStatus('confirmed')
    setSummaryError(false)
  }, [open, defaultStart, defaultEnd, defaultAllDay])

  const handleAllDayToggle = (checked: boolean) => {
    setAllDay(checked)
    if (checked && startAt) {
      setStartAt(startAt.slice(0, 10))
      setEndAt(endAt.slice(0, 10) || startAt.slice(0, 10))
    } else if (!checked && startAt) {
      setStartAt(`${startAt.slice(0, 10)}T09:00`)
      setEndAt(`${(endAt || startAt).slice(0, 10)}T10:00`)
    }
  }

  const handleSubmit = () => {
    if (!summary.trim()) {
      setSummaryError(true)
      return
    }

    setSubmitting(true)

    // A timed event is an instant: the browser's wall-clock time, made explicit.
    // An all-day event is a pair of dates, the end excluded as in Google, and nothing
    // else (MAG-382): the field holds the last day, the API wants the day after.
    const timing = allDay
      ? { allDay: true, startDate: startAt, endDate: endDateOf(endAt || startAt) }
      : { allDay: false, startAt: new Date(startAt).toISOString(), endAt: new Date(endAt).toISOString() }

    dataProvider
      .create('events', {
        data: {
          summary: summary.trim(),
          ...timing,
          agenda: calendarId,
          status,
          ...(rrule ? { rrule } : {}),
          ...(reminders ? { reminders } : {}),
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

  const inputType = allDay ? 'date' : 'datetime-local'

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Nouvel événement</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Résumé"
            value={summary}
            onChange={(e) => {
              setSummary(e.target.value)
              if (e.target.value.trim()) setSummaryError(false)
            }}
            error={summaryError}
            helperText={summaryError ? 'Le résumé est requis' : undefined}
            required
            autoFocus
          />
          <FormControlLabel
            control={
              <Switch
                checked={allDay}
                onChange={(e) => handleAllDayToggle(e.target.checked)}
              />
            }
            label="Journée entière"
          />
          <TextField
            label="Début"
            type={inputType}
            value={startAt}
            onChange={(e) => setStartAt(e.target.value)}
            required
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="Fin"
            type={inputType}
            value={endAt}
            onChange={(e) => setEndAt(e.target.value)}
            required
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <RecurrencePicker
            value={rrule}
            onChange={setRrule}
            eventStartDate={startAt ? (allDay ? parseDay(startAt) : new Date(startAt)) : null}
          />
          <ReminderPicker value={reminders} onChange={setReminders} />
          <TextField
            label="Statut"
            value={status}
            onChange={(e) => setStatus(e.target.value as EventStatus)}
            select
          >
            <MenuItem value="confirmed">Confirmé</MenuItem>
            <MenuItem value="tentative">Provisoire</MenuItem>
          </TextField>
          <TextField
            label="Calendrier"
            value={calendarId}
            onChange={(e) => setCalendarId(e.target.value)}
            select
            required
          >
            {calendars.map((cal) => (
              <MenuItem key={cal.id} value={cal.id}>
                {cal.name}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button onClick={handleSubmit} variant="contained" disabled={submitting}>
          Créer
        </Button>
      </DialogActions>
    </Dialog>
  )
}
