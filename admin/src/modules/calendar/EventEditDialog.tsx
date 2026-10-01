import { useEffect, useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'

export interface EditableEvent {
  summary: string
  start: string
  end: string
  allDay: boolean
  description?: string
  location?: string
}

export interface EventEditValues {
  summary: string
  startAt: string
  endAt: string
  allDay: boolean
  description: string | null
  location: string | null
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
  const [summaryError, setSummaryError] = useState(false)

  useEffect(() => {
    if (!open || !event) return
    const start = new Date(event.start)
    const end = new Date(event.end)
    setSummary(event.summary)
    setAllDay(event.allDay)
    if (event.allDay) {
      // FullCalendar's all-day end is exclusive: show the last included day
      const lastDay = new Date(end)
      if (lastDay.getTime() > start.getTime()) lastDay.setDate(lastDay.getDate() - 1)
      setStartAt(toLocalDate(start))
      setEndAt(toLocalDate(lastDay))
    } else {
      setStartAt(toLocalDatetime(start))
      setEndAt(toLocalDatetime(end))
    }
    setLocation(event.location ?? '')
    setDescription(event.description ?? '')
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
      startAt: allDay ? `${startAt}T00:00:00Z` : new Date(startAt).toISOString(),
      endAt: allDay ? `${endAt}T23:59:59Z` : new Date(endAt).toISOString(),
      allDay,
      description: description.trim() || null,
      location: location.trim() || null,
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
