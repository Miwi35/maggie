import { useState } from 'react'
import type { FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormGroup from '@mui/material/FormGroup'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { Placement } from './placementsApi'
import type { Project } from './projectsApi'
import { emptyValues, toInput, valuesOf, validate } from './scenarioForm'
import type { ScenarioFormErrors, ScenarioFormValues } from './scenarioForm'
import { ScenarioProjectPicker } from './ScenarioProjectPicker'
import { ScenarioRepaymentRows } from './ScenarioRepaymentRows'
import type { LoanChoice, Scenario, ScenarioInput } from './scenariosApi'

interface Props {
  /** The scenario being edited; absent when creating. */
  scenario?: Scenario
  projects: Project[]
  placements: Placement[]
  loans: LoanChoice[]
  onClose: () => void
  onSubmit: (input: ScenarioInput) => Promise<void>
}

/** Creating or editing a scenario: its name, the projects it keeps and their order, repayments, placements and monthly saving. */
export const ScenarioEditor = ({ scenario, projects, placements, loans, onClose, onSubmit }: Props) => {
  const [values, setValues] = useState<ScenarioFormValues>(() =>
    scenario
      ? valuesOf(
          scenario,
          projects.map((project) => project.id),
        )
      : emptyValues(projects.map((project) => project.id)),
  )
  const [errors, setErrors] = useState<ScenarioFormErrors>({})
  const [failure, setFailure] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const set = <K extends keyof ScenarioFormValues>(key: K, value: ScenarioFormValues[K]) =>
    setValues((previous) => ({ ...previous, [key]: value }))

  const togglePlacement = (id: string, checked: boolean) =>
    set('placementIds', checked ? [...values.placementIds, id] : values.placementIds.filter((candidate) => candidate !== id))

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const found = validate(values)
    setErrors(found)
    setFailure(null)
    if (Object.keys(found).length > 0) return

    setSaving(true)
    try {
      await onSubmit(toInput(values))
    } catch (error) {
      setFailure(error instanceof Error && error.message ? error.message : "Le scénario n'a pas été enregistré.")
    } finally {
      setSaving(false)
    }
  }

  const title = scenario ? `Modifier « ${scenario.name} »` : 'Nouveau scénario'

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <form onSubmit={submit} noValidate aria-label={title}>
        <DialogTitle>{title}</DialogTitle>
        <DialogContent>
          <Stack spacing={3} sx={{ pt: 1 }}>
            {failure && <Alert severity="error">{failure}</Alert>}
            <TextField
              label="Nom"
              required
              value={values.name}
              onChange={(event) => set('name', event.target.value)}
              error={Boolean(errors.name)}
              helperText={errors.name}
              slotProps={{ htmlInput: { maxLength: 120 } }}
            />
            <ScenarioProjectPicker projects={projects} selected={values.projectIds} onChange={(ids) => set('projectIds', ids)} />
            <ScenarioRepaymentRows rows={values.repayments} loans={loans} onChange={(rows) => set('repayments', rows)} />
            {errors.repayments && <Alert severity="error">{errors.repayments}</Alert>}
            {placements.length > 0 && (
              <FormGroup>
                <Typography variant="subtitle2">Placements</Typography>
                {placements.map((placement) => (
                  <FormControlLabel
                    key={placement.id}
                    control={
                      <Checkbox
                        size="small"
                        checked={values.placementIds.includes(placement.id)}
                        onChange={(event) => togglePlacement(placement.id, event.target.checked)}
                      />
                    }
                    label={placement.name}
                  />
                ))}
              </FormGroup>
            )}
            <TextField
              label="Épargne mensuelle (€)"
              value={values.saving}
              onChange={(event) => set('saving', event.target.value)}
              error={Boolean(errors.saving)}
              helperText={errors.saving}
              slotProps={{ htmlInput: { inputMode: 'decimal' } }}
              sx={{ maxWidth: 240 }}
            />
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
