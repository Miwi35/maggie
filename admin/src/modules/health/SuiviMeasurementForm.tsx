import { useEffect, useState } from 'react'
import { useCreate, useNotify, useUpdate } from 'react-admin'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { BODY_MEASUREMENTS } from './bodyMeasurementsApi'
import type { BodyMeasurement } from './bodyMeasurementsApi'
import { emptyValues, toPayload, validate, valuesOf } from './bodyMeasurementPayload'
import type { MeasurementErrors, MeasurementValues } from './bodyMeasurementPayload'

interface SuiviMeasurementFormProps {
  open: boolean
  /** The weighing to edit; none to add one. */
  measurement?: BodyMeasurement
  onClose: () => void
}

const decimalField = { inputMode: 'decimal' as const }

/**
 * The one dialog a weighing is added and edited in. A weighing added here is
 * « Saisie » (`manual`); one edited keeps its source and its « écartée ».
 */
export const SuiviMeasurementForm = ({ open, measurement, onClose }: SuiviMeasurementFormProps) => {
  const notify = useNotify()
  const [create, { isPending: creating }] = useCreate()
  const [update, { isPending: updating }] = useUpdate()
  const [values, setValues] = useState<MeasurementValues>(() => emptyValues())
  const [errors, setErrors] = useState<MeasurementErrors>({})

  useEffect(() => {
    if (!open) return
    setValues(measurement ? valuesOf(measurement) : emptyValues())
    setErrors({})
  }, [open, measurement])

  const change = (name: keyof MeasurementValues) => (event: { target: { value: string } }) => {
    setValues((previous) => ({ ...previous, [name]: event.target.value }))
    setErrors((previous) => ({ ...previous, [name]: undefined }))
  }

  const submit = () => {
    const found = validate(values)
    setErrors(found)
    if (Object.values(found).some(Boolean)) return

    const payload = toPayload(values)
    if (measurement) {
      update(
        BODY_MEASUREMENTS,
        { id: measurement.id, data: payload, previousData: measurement },
        {
          onSuccess: onClose,
          onError: () => notify('Erreur : la pesée n’a pas été modifiée.', { type: 'error' }),
        },
      )

      return
    }
    create(
      BODY_MEASUREMENTS,
      { data: { ...payload, source: 'manual' } },
      {
        onSuccess: onClose,
        onError: () => notify('Erreur : la pesée n’a pas été ajoutée.', { type: 'error' }),
      },
    )
  }

  const field = (name: keyof MeasurementValues) => ({
    value: values[name],
    onChange: change(name),
    error: Boolean(errors[name]),
    helperText: errors[name],
    fullWidth: true,
  })

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{measurement ? 'Modifier la pesée' : 'Ajouter une pesée'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Date et heure" type="datetime-local" slotProps={{ inputLabel: { shrink: true } }} {...field('measuredAt')} />
          <TextField label="Poids (kg)" slotProps={{ htmlInput: decimalField }} {...field('weightKg')} />
          <TextField label="Masse grasse (%)" slotProps={{ htmlInput: decimalField }} {...field('bodyFatPercent')} />
          <TextField label="Tour de taille (cm)" slotProps={{ htmlInput: decimalField }} {...field('waistCm')} />
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
