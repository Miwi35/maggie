import { useEffect, useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import { endDateOf, lastDayOf } from '../../dates'
import { ReminderPicker } from './ReminderPicker'
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
}

interface EventEditDialogProps {
  open: boolean
  event: EditableEvent | null
  onClose: () => void
  onSubmit: (values: EventEditValues) => void
}

const pad = (n: number) => String(n).padStart(2, '0')

const toLocalDate = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

const toLocalDatetime = (d: Date): string => `${toLocalDate(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`

export const EventEditDialog = ({ open, event, onClose, onSubmit }: EventEditDialogProps) => {
  const [summary, setSummary] = useState('')
  const [allDay, setAllDay] = useState(false)
  const [startAt, setStartAt] = useState('')
  const [endAt, setEndAt] = useState('')
  const [location, setLocation] = useState('')
  const [description, setDescription] = useState('')
  const [reminders, setReminders] = useState<EventReminders | null>(null)
  const [status, setStatus] = useState<EventStatus>('confirmed')
  const [summaryError, setSummaryError] = useState(false)

  useEffect(() => {
    if (!open || !event) return
    setSummary(event.summary)
    setAllDay(event.allDay)
    if (event.allDay) {
      // The owner types and reads the last day included, as in Google Agenda; the
      // event carries the exclusive end. Dates both: no time zone to cross.
      const first = event.start.slice(0, 10)
      setStartAt(first)
      setEndAt(event.end ? lastDayOf(event.end.slice(0, 10)) : first)
    } else {
      setStartAt(toLocalDatetime(new Date(event.start)))
      setEndAt(toLocalDatetime(new Date(event.end)))
    }
    setLocation(event.location ?? '')
    setDescription(event.description ?? '')
    setReminders(event.reminders ?? null)
    setStatus(event.status ?? 'confirmed')
    setSummaryError(false)
  }, [open, event])

  const handleAllDayToggle = (checked: boolean) => {
    setAllDay(checked)
    if (checked) {
      setStartAt(startAt.slice(0, 10))
      setEndAt(endAt.slice(0, 10) || startAt.slice(0, 10))
    } else {
      setStartAt(`${startAt.slice(0, 10)}T09:00`)
      setEndAt(`${(endAt || startAt).slice(0, 10)}T10:00`)
    }
  }

  const handleSubmit = () => {
    if (!summary.trim()) {
      setSummaryError(true)
      return
    }
    onSubmit({
      summary: summary.trim(),
      ...(allDay
        ? { startDate: startAt, endDate: endDateOf(endAt || startAt), startAt: null, endAt: null }
        : { startAt: new Date(startAt).toISOString(), endAt: new Date(endAt).toISOString(), startDate: null, endDate: null }),
      allDay,
      description: description.trim() || null,
      location: location.trim() || null,
      reminders,
      status,
    })
  }

  const inputType = allDay ? 'date' : 'datetime-local'

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Modifier l&apos;événement</DialogTitle>
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
            control={<Switch checked={allDay} onChange={(e) => handleAllDayToggle(e.target.checked)} />}
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
          <TextField label="Lieu" value={location} onChange={(e) => setLocation(e.target.value)} />
          <TextField
            label="Description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button onClick={handleSubmit} variant="contained">
          Enregistrer
        </Button>
      </DialogActions>
    </Dialog>
  )
}
