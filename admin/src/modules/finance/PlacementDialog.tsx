import { useState } from 'react'
import type { FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { FREQUENCY_CHOICES, PlacementError } from './placementsApi'
import type { Placement, PlacementFrequency, PlacementInput } from './placementsApi'
import { emptyValues, toInput, validatePlacement, valuesOf } from './placementForm'
import type { PlacementFormErrors, PlacementFormField, PlacementFormValues } from './placementForm'

const API_FIELDS: Record<string, PlacementFormField> = {
  name: 'name',
  initial: 'initial',
  deposit: 'deposit',
  annualRate: 'rate',
  years: 'years',
}

interface PlacementDialogProps {
  /** The placement being edited; absent when creating. */
  placement?: Placement
  onClose: () => void
  onSubmit: (input: PlacementInput) => Promise<void>
}

/** Creating or editing a simulated placement (MAG-555). */
export const PlacementDialog = ({ placement, onClose, onSubmit }: PlacementDialogProps) => {
  const [values, setValues] = useState<PlacementFormValues>(() => (placement ? valuesOf(placement) : emptyValues()))
  const [errors, setErrors] = useState<PlacementFormErrors>({})
  const [failure, setFailure] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const set = <K extends keyof PlacementFormValues>(key: K, value: PlacementFormValues[K]) =>
    setValues((previous) => ({ ...previous, [key]: value }))

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const found = validatePlacement(values)
    setErrors(found)
    setFailure(null)
    if (Object.keys(found).length > 0) return

    setSaving(true)
    try {
      await onSubmit(toInput(values))
    } catch (error) {
      const message = error instanceof Error && error.message ? error.message : "Le placement n'a pas été enregistré."
      const field = error instanceof PlacementError && error.field ? API_FIELDS[error.field] : undefined
      if (field) setErrors({ [field]: message })
      else setFailure(message)
    } finally {
      setSaving(false)
    }
  }

  const amountField = (key: 'initial' | 'deposit', label: string) => (
    <TextField
      label={label}
      value={values[key]}
      onChange={(event) => set(key, event.target.value)}
      error={Boolean(errors[key])}
      helperText={errors[key]}
      slotProps={{ htmlInput: { inputMode: 'decimal' } }}
      fullWidth
    />
  )

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={submit} noValidate aria-label={placement ? `Modifier ${placement.name}` : 'Nouveau placement'}>
        <DialogTitle>{placement ? `Modifier « ${placement.name} »` : 'Nouveau placement'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {failure && <Alert severity="error">{failure}</Alert>}
            <TextField
              label="Nom"
              required
              value={values.name}
              onChange={(event) => set('name', event.target.value)}
              error={Boolean(errors.name)}
              helperText={errors.name}
              autoFocus
            />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              {amountField('initial', 'Versement initial (€)')}
              {amountField('deposit', 'Versement (€)')}
            </Stack>
            <TextField
              select
              label="Fréquence"
              value={values.frequency}
              onChange={(event) => set('frequency', event.target.value as PlacementFrequency)}
              helperText={values.frequency === 'once' ? 'Un seul versement, le premier jour.' : undefined}
            >
              {FREQUENCY_CHOICES.map((choice) => (
                <MenuItem key={choice.id} value={choice.id}>
                  {choice.label}
                </MenuItem>
              ))}
            </TextField>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Rendement annuel (%)"
                required
                value={values.rate}
                onChange={(event) => set('rate', event.target.value)}
                error={Boolean(errors.rate)}
                helperText={errors.rate}
                slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                fullWidth
              />
              <TextField
                label="Durée (ans)"
                required
                value={values.years}
                onChange={(event) => set('years', event.target.value)}
                error={Boolean(errors.years)}
                helperText={errors.years}
                slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                fullWidth
              />
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Annuler</Button>
          <Button type="submit" variant="contained" disabled={saving}>
            Enregistrer
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
