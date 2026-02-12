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

interface TaskData {
  id: string
  name: string
  description?: string
  priority: string
  criticality: string
  dueDate?: string
  doneDate?: string
}

interface TaskEditDialogProps {
  open: boolean
  task: TaskData | null
  onClose: () => void
  onUpdated: () => void
}

const toLocalDate = (isoString: string): string => {
  // dueDate comes as "2026-03-15T00:00:00+00:00" or similar
  return isoString.slice(0, 10)
}

export const TaskEditDialog = ({ open, task, onClose, onUpdated }: TaskEditDialogProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('medium')
  const [criticality, setCriticality] = useState('low')
  const [dueDate, setDueDate] = useState('')
  const [isDone, setIsDone] = useState(false)
  const [nameError, setNameError] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!open || !task) return
    setName(task.name)
    setDescription(task.description || '')
    setPriority(task.priority)
    setCriticality(task.criticality)
    setDueDate(task.dueDate ? toLocalDate(task.dueDate) : '')
    setIsDone(task.doneDate != null)
    setNameError(false)
  }, [open, task])

  const handleSubmit = () => {
    if (!task) return
    if (!name.trim()) {
      setNameError(true)
      return
    }

    setSubmitting(true)

    dataProvider
      .update('tasks', {
        id: task.id,
        data: {
          name: name.trim(),
          description: description.trim() || null,
          priority,
          criticality,
          ...(dueDate ? { dueDate: `${dueDate}T00:00:00` } : { dueDate: null }),
          doneDate: isDone && !task.doneDate ? new Date().toISOString() : isDone ? task.doneDate : null,
        },
        previousData: task,
      })
      .then(() => {
        notify('Tâche mise à jour', { type: 'success' })
        onUpdated()
        onClose()
      })
      .catch((error: Error) => {
        notify(`Erreur: ${error.message}`, { type: 'error' })
      })
      .finally(() => setSubmitting(false))
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Modifier la tâche</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Nom"
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              if (e.target.value.trim()) setNameError(false)
            }}
            error={nameError}
            helperText={nameError ? 'Le nom est requis' : undefined}
            required
            autoFocus
          />
          <TextField
            label="Description"
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
          <FormControlLabel
            control={<Switch checked={isDone} onChange={(e) => setIsDone(e.target.checked)} />}
            label="Terminée"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button onClick={handleSubmit} variant="contained" disabled={submitting}>
          Enregistrer
        </Button>
      </DialogActions>
    </Dialog>
  )
}
