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

interface Calendar {
  id: string
  name: string
  isDefault: boolean
}

interface EventCreateDialogProps {
  open: boolean
  onClose: () => void
  onCreated: () => void
  defaultDate?: string
}

const STATUS_OPTIONS = [
  { value: 'confirmed', label: 'Confirmé' },
  { value: 'tentative', label: 'Provisoire' },
  { value: 'cancelled', label: 'Annulé' },
]

export const EventCreateDialog = ({ open, onClose, onCreated, defaultDate }: EventCreateDialogProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [calendars, setCalendars] = useState<Calendar[]>([])
  const [summary, setSummary] = useState('')
  const [startAt, setStartAt] = useState('')
  const [endAt, setEndAt] = useState('')
  const [allDay, setAllDay] = useState(false)
  const [calendarId, setCalendarId] = useState('')
  const [status, setStatus] = useState('confirmed')
  const [summaryError, setSummaryError] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  // Load calendars
  useEffect(() => {
    if (!open) return
    dataProvider
      .getList('calendars', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'name', order: 'ASC' },
        filter: {},
      })
      .then(({ data }) => {
        const cals = data as unknown as Calendar[]
        setCalendars(cals)
        const defaultCal = cals.find((c) => c.isDefault) ?? cals[0]
        if (defaultCal && !calendarId) {
          setCalendarId(defaultCal.id)
        }
      })
      .catch(console.error)
  }, [open, dataProvider]) // eslint-disable-line react-hooks/exhaustive-deps

  // Pre-fill dates when dialog opens
  useEffect(() => {
    if (!open) return
    if (defaultDate) {
      setStartAt(allDay ? defaultDate : `${defaultDate}T09:00`)
      setEndAt(allDay ? defaultDate : `${defaultDate}T10:00`)
    } else {
      setStartAt('')
      setEndAt('')
    }
    setSummary('')
    setAllDay(false)
    setStatus('confirmed')
    setSummaryError(false)
  }, [open, defaultDate]) // eslint-disable-line react-hooks/exhaustive-deps

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

    const startDate = allDay ? `${startAt}T00:00:00` : `${startAt}:00`
    const endDate = allDay ? `${endAt}T23:59:59` : `${endAt}:00`

    dataProvider
      .create('events', {
        data: {
          summary: summary.trim(),
          startAt: startDate,
          endAt: endDate,
          allDay,
          calendar: calendarId,
          status,
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
          <TextField
            label="Statut"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            select
          >
            {STATUS_OPTIONS.map((opt) => (
              <MenuItem key={opt.value} value={opt.value}>
                {opt.label}
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
