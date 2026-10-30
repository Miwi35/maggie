import { useEffect, useState } from 'react'
import { useDataProvider } from 'react-admin'
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
import { parseDay } from '../../dates'
import { RecurrencePicker } from './RecurrencePicker'
import { rruleToFrenchText } from './recurrenceUtils'
import { ReminderPicker } from './ReminderPicker'
import type { EventReminders } from './ReminderPicker'
import type { EventStatus } from './eventStatus'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'

interface Agenda {
  id: string
  name: string
  default: boolean
}

/** What the fields hold: dates and times as the inputs write them, in local time. */
export interface EventFormValues {
  summary: string
  allDay: boolean
  /** `YYYY-MM-DD` when all-day, else `YYYY-MM-DDTHH:mm`. */
  startAt: string
  endAt: string
  rrule: string | null
  status: EventStatus
  reminders: EventReminders | null
  /** The agenda's IRI, as the data provider hands it over. Empty until one is picked. */
  agenda: string
  location: string
  description: string
}

interface EventFormDialogProps {
  open: boolean
  title: string
  submitLabel: string
  initial: EventFormValues
  /** Set on an occurrence of a series: the rule is the series', shown but not editable here. */
  seriesRrule?: string | null
  submitting?: boolean
  onClose: () => void
  onSubmit: (values: EventFormValues) => void
}

const pad = (n: number) => String(n).padStart(2, '0')

export const toLocalDate = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

export const toLocalDatetime = (d: Date): string => `${toLocalDate(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`

/**
 * The one dialog an event is created and edited in (MAG-649): the same fields in the
 * same order — Titre, Toute la journée, Début, Fin, Répéter, Statut, Rappels (hors journée entière), Agenda,
 * Lieu, Description — whichever way the owner came.
 */
export const EventFormDialog = ({
  open,
  title,
  submitLabel,
  initial,
  seriesRrule = null,
  submitting = false,
  onClose,
  onSubmit,
}: EventFormDialogProps) => {
  const dataProvider = useDataProvider()

  const [agendas, setAgendas] = useState<Agenda[]>([])
  const [summary, setSummary] = useState('')
  const [allDay, setAllDay] = useState(false)
  const [startAt, setStartAt] = useState('')
  const [endAt, setEndAt] = useState('')
  const [rrule, setRrule] = useState<string | null>(null)
  const [status, setStatus] = useState<EventStatus>('confirmed')
  const [reminders, setReminders] = useState<EventReminders | null>(null)
  const [agenda, setAgenda] = useState('')
  const [location, setLocation] = useState('')
  const [description, setDescription] = useState('')
  const [summaryError, setSummaryError] = useState(false)

  useEffect(() => {
    if (!open) return
    dataProvider
      .getList('agendas', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'name', order: 'ASC' },
        filter: {},
      })
      .then(({ data }) => {
        setAgendas(data as unknown as Agenda[])
      })
      .catch(console.error)
  }, [open, dataProvider])

  // No agenda chosen yet: the default one, as soon as the list is there.
  useEffect(() => {
    if (!open || agenda) return
    const fallback = agendas.find((a) => a.default) ?? agendas[0]
    if (fallback) setAgenda(fallback.id)
  }, [open, agenda, agendas])

  useEffect(() => {
    if (!open) return
    setSummary(initial.summary)
    setAllDay(initial.allDay)
    setStartAt(initial.startAt)
    setEndAt(initial.endAt)
    setRrule(initial.rrule)
    setStatus(initial.status)
    setReminders(initial.reminders)
    setAgenda(initial.agenda)
    setLocation(initial.location)
    setDescription(initial.description)
    setSummaryError(false)
  }, [open, initial])

  const handleAllDayToggle = (checked: boolean) => {
    setAllDay(checked)
    if (!startAt) return
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
      allDay,
      startAt,
      endAt,
      rrule,
      status,
      reminders: allDay ? null : reminders,
      agenda,
      location: location.trim(),
      description: description.trim(),
    })
  }

  const inputType = allDay ? 'date' : 'datetime-local'

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Titre"
            value={summary}
            onChange={(e) => {
              setSummary(e.target.value)
              if (e.target.value.trim()) setSummaryError(false)
            }}
            error={summaryError}
            helperText={summaryError ? 'Le titre est requis' : undefined}
            required
            autoFocus
          />
          <FormControlLabel
            control={<Switch checked={allDay} onChange={(e) => handleAllDayToggle(e.target.checked)} />}
            label="Toute la journée"
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
          {seriesRrule ? (
            <TextField
              label="Répéter"
              value={rruleToFrenchText(seriesRrule)}
              disabled
            />
          ) : (
            <RecurrencePicker
              value={rrule}
              onChange={setRrule}
              eventStartDate={startAt ? (allDay ? parseDay(startAt) : new Date(startAt)) : null}
            />
          )}
          <TextField label="Statut" value={status} onChange={(e) => setStatus(e.target.value as EventStatus)} select>
            <MenuItem value="confirmed">Confirmé</MenuItem>
            <MenuItem value="tentative">Provisoire</MenuItem>
          </TextField>
          {!allDay && <ReminderPicker value={reminders} onChange={setReminders} />}
          <TextField label="Agenda" value={agenda} onChange={(e) => setAgenda(e.target.value)} select required>
            {agendas.map((a) => (
              <MenuItem key={a.id} value={a.id}>
                {a.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField label="Lieu" value={location} onChange={(e) => setLocation(e.target.value)} />
          <MarkdownEditor label="Description" value={description} onChange={setDescription} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button onClick={handleSubmit} variant="contained" disabled={submitting || !agenda}>
          {submitLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
