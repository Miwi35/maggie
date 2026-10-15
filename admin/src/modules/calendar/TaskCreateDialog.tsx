import { useEffect, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { localDay } from '../../dates'
import { EstimateFields, parseMinutes } from './TaskEditDialog'

interface TaskCreateDialogProps {
  open: boolean
  onClose: () => void
  onCreated: () => void
  defaultDueDate?: Date
}

export const TaskCreateDialog = ({ open, onClose, onCreated, defaultDueDate }: TaskCreateDialogProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('medium')
  const [criticality, setCriticality] = useState('low')
  const [dueDate, setDueDate] = useState('')
  const [minutes, setMinutes] = useState('')
  const [firstStep, setFirstStep] = useState('')
  const [titleError, setTitleError] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!open) return
    setTitle('')
    setDescription('')
    setPriority('medium')
    setCriticality('low')
    setDueDate(defaultDueDate ? localDay(defaultDueDate) : '')
    setMinutes('')
    setFirstStep('')
    setTitleError(false)
  }, [open, defaultDueDate])

  const handleSubmit = () => {
    if (!title.trim()) {
      setTitleError(true)
      return
    }
    const estimatedMinutes = parseMinutes(minutes)
    if (Number.isNaN(estimatedMinutes)) return

    setSubmitting(true)

    dataProvider
      .create('tasks', {
        data: {
          title: title.trim(),
          ...(description.trim() ? { description: description.trim() } : {}),
          priority,
          criticality,
          // A plain day, the field's own type: no zone can move it (MAG-404).
          ...(dueDate ? { dueDate } : {}),
          ...(estimatedMinutes != null ? { estimatedMinutes } : {}),
          ...(firstStep.trim() ? { firstStep: firstStep.trim() } : {}),
        },
      })
      .then(() => {
        notify('Tâche créée', { type: 'success' })
        onCreated()
        onClose()
      })
      .catch((error: Error) => {
        notify(`Erreur: ${error.message}`, { type: 'error' })
      })
      .finally(() => setSubmitting(false))
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Nouvelle tâche</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Titre"
            value={title}
            onChange={(e) => {
              setTitle(e.target.value)
              if (e.target.value.trim()) setTitleError(false)
            }}
            error={titleError}
            helperText={titleError ? 'Le titre est requis' : undefined}
            required
            autoFocus
          />
          <TextField
            label="Notes"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            multiline
            minRows={2}
          />
          <TextField
            label="Priorité"
            value={priority}
            onChange={(e) => setPriority(e.target.value)}
            select
          >
            <MenuItem value="low">Basse</MenuItem>
            <MenuItem value="medium">Moyenne</MenuItem>
            <MenuItem value="high">Haute</MenuItem>
          </TextField>
          <TextField
            label="Criticité"
            value={criticality}
            onChange={(e) => setCriticality(e.target.value)}
            select
          >
            <MenuItem value="low">Basse</MenuItem>
            <MenuItem value="medium">Moyenne</MenuItem>
            <MenuItem value="high">Haute</MenuItem>
            <MenuItem value="critical">Critique</MenuItem>
          </TextField>
          <TextField
            label="Échéance"
            type="date"
            value={dueDate}
            onChange={(e) => setDueDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <EstimateFields minutes={minutes} onMinutes={setMinutes} firstStep={firstStep} onFirstStep={setFirstStep} />
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
