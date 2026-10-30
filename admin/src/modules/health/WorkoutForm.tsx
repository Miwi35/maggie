import { useEffect, useState } from 'react'
import { useCreate, useNotify, useUpdate } from 'react-admin'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import {
  FEELING_LABELS,
  TYPE_LABELS,
  WORKOUT_FEELINGS,
  WORKOUT_TYPES,
} from './workoutFormat'
import type { Workout } from './workoutFormat'
import { emptyValues, toPayload, validate, valuesOf } from './workoutPayload'
import type { WorkoutErrors, WorkoutValues } from './workoutPayload'

interface WorkoutFormProps {
  open: boolean
  /** The workout to edit; none to add one. */
  workout?: Workout
  onClose: () => void
  /** The id (IRI) of the workout just saved. */
  onSaved?: (id: string) => void
}

const numberField = { inputMode: 'numeric' as const }

/**
 * The one dialog a workout is added and edited in. The source is not a field:
 * a workout added here is « Saisie » (`manual`), one edited keeps its own.
 */
export const WorkoutForm = ({ open, workout, onClose, onSaved }: WorkoutFormProps) => {
  const notify = useNotify()
  const [create, { isPending: creating }] = useCreate()
  const [update, { isPending: updating }] = useUpdate()
  const [values, setValues] = useState<WorkoutValues>(() => emptyValues())
  const [errors, setErrors] = useState<WorkoutErrors>({})

  useEffect(() => {
    if (!open) return
    setValues(workout ? valuesOf(workout) : emptyValues())
    setErrors({})
  }, [open, workout])

  const change = (field: keyof WorkoutValues) => (event: { target: { value: string } }) => {
    setValues((previous) => ({ ...previous, [field]: event.target.value }))
    setErrors((previous) => ({ ...previous, [field]: undefined }))
  }

  const submit = () => {
    const found = validate(values)
    setErrors(found)
    if (Object.values(found).some(Boolean)) return

    const payload = toPayload(values)
    if (workout) {
      update(
        'workouts',
        { id: workout.id, data: payload, previousData: workout },
        {
          onSuccess: () => {
            onSaved?.(workout.id)
            onClose()
          },
          onError: () => notify('Erreur : la séance n’a pas été modifiée.', { type: 'error' }),
        },
      )

      return
    }
    create(
      'workouts',
      { data: { ...payload, source: 'manual' } },
      {
        onSuccess: (created) => {
          onSaved?.(String(created.id))
          onClose()
        },
        onError: () => notify('Erreur : la séance n’a pas été ajoutée.', { type: 'error' }),
      },
    )
  }

  const field = (name: keyof WorkoutValues) => ({
    value: values[name],
    onChange: change(name),
    error: Boolean(errors[name]),
    helperText: errors[name],
    fullWidth: true,
  })

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{workout ? 'Modifier la séance' : 'Ajouter une séance'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Type" {...field('type')}>
            {WORKOUT_TYPES.map((type) => (
              <MenuItem key={type} value={type}>
                {TYPE_LABELS[type]}
              </MenuItem>
            ))}
          </TextField>
          <TextField label="Titre" {...field('title')} />
          <TextField
            label="Date et heure de début"
            type="datetime-local"
            slotProps={{ inputLabel: { shrink: true } }}
            {...field('startedAt')}
          />
          <Stack direction="row" spacing={2}>
            <TextField label="Durée (minutes)" slotProps={{ htmlInput: numberField }} {...field('durationMinutes')} />
            <TextField label="Distance (km)" slotProps={{ htmlInput: { inputMode: 'decimal' } }} {...field('distanceKm')} />
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField label="kcal" slotProps={{ htmlInput: numberField }} {...field('kcal')} />
            <TextField label="FC moyenne (bpm)" slotProps={{ htmlInput: numberField }} {...field('avgHeartRate')} />
          </Stack>
          <TextField select label="Ressenti" {...field('feeling')}>
            <MenuItem value="">Aucun</MenuItem>
            {WORKOUT_FEELINGS.map((feeling) => (
              <MenuItem key={feeling} value={feeling}>
                {FEELING_LABELS[feeling]}
              </MenuItem>
            ))}
          </TextField>
          <TextField label="Notes" multiline minRows={2} {...field('notes')} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button variant="contained" onClick={submit} disabled={creating || updating}>
          Enregistrer
        </Button>
      </DialogActions>
    </Dialog>
  )
}
