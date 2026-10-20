import { useState } from 'react'
import type { FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormGroup from '@mui/material/FormGroup'
import FormLabel from '@mui/material/FormLabel'
import MenuItem from '@mui/material/MenuItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { DATE_MODE_CHOICES, ProjectError, WELLBEING_CHOICES } from './projectsApi'
import type { Project, ProjectDateMode, ProjectInput, ProjectWellbeing } from './projectsApi'
import { emptyValues, toInput, validateProject, valuesOf } from './projectForm'
import type { ProjectFormErrors, ProjectFormField, ProjectFormValues } from './projectForm'

const API_FIELDS: Record<string, ProjectFormField> = { name: 'name', amountCents: 'amount', targetOn: 'targetOn' }

interface ProjectDialogProps {
  /** The project being edited; absent when creating. */
  project?: Project
  /** Every project, to choose the ones this one waits for. */
  projects: Project[]
  onClose: () => void
  onSubmit: (input: ProjectInput) => Promise<void>
}

/** Creating or editing a project (MAG-519). */
export const ProjectDialog = ({ project, projects, onClose, onSubmit }: ProjectDialogProps) => {
  const [values, setValues] = useState<ProjectFormValues>(() => (project ? valuesOf(project) : emptyValues()))
  const [errors, setErrors] = useState<ProjectFormErrors>({})
  const [failure, setFailure] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const others = projects.filter((candidate) => candidate.id !== project?.id)

  const set = <K extends keyof ProjectFormValues>(key: K, value: ProjectFormValues[K]) =>
    setValues((previous) => ({ ...previous, [key]: value }))

  const toggleDependency = (id: string, checked: boolean) =>
    set('dependsOn', checked ? [...values.dependsOn, id] : values.dependsOn.filter((candidate) => candidate !== id))

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const found = validateProject(values)
    setErrors(found)
    setFailure(null)
    if (Object.keys(found).length > 0) return

    setSaving(true)
    try {
      await onSubmit(toInput(values))
    } catch (error) {
      const message = error instanceof Error ? error.message : "Le projet n'a pas été enregistré."
      const field = error instanceof ProjectError && error.field ? API_FIELDS[error.field] : undefined
      if (field) setErrors({ [field]: message })
      else setFailure(message)
    } finally {
      setSaving(false)
    }
  }

  const dateLabel = values.dateMode === 'deadline' ? 'Date limite' : 'Date du projet'

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={submit} noValidate aria-label={project ? `Modifier ${project.name}` : 'Nouveau projet'}>
        <DialogTitle>{project ? `Modifier « ${project.name} »` : 'Nouveau projet'}</DialogTitle>
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
            <TextField
              label="Montant (€)"
              required
              value={values.amount}
              onChange={(event) => set('amount', event.target.value)}
              error={Boolean(errors.amount)}
              helperText={errors.amount}
              slotProps={{ htmlInput: { inputMode: 'decimal' } }}
            />
            <TextField
              select
              label="Bien-être"
              value={values.wellbeing}
              onChange={(event) => set('wellbeing', event.target.value as ProjectWellbeing)}
            >
              {WELLBEING_CHOICES.map((choice) => (
                <MenuItem key={choice.id} value={choice.id}>
                  {choice.label} ({choice.score})
                </MenuItem>
              ))}
            </TextField>
            <FormControl>
              <FormLabel id="project-date-mode">Échéance</FormLabel>
              <RadioGroup
                row
                aria-labelledby="project-date-mode"
                value={values.dateMode}
                onChange={(event) => set('dateMode', event.target.value as ProjectDateMode)}
              >
                {DATE_MODE_CHOICES.map((choice) => (
                  <FormControlLabel
                    key={choice.id}
                    value={choice.id}
                    control={<Radio />}
                    label={`${choice.label} (${choice.hint})`}
                  />
                ))}
              </RadioGroup>
            </FormControl>
            {values.dateMode !== 'none' && (
              <TextField
                type="date"
                label={dateLabel}
                required
                value={values.targetOn}
                onChange={(event) => set('targetOn', event.target.value)}
                error={Boolean(errors.targetOn)}
                helperText={errors.targetOn}
                slotProps={{ inputLabel: { shrink: true } }}
              />
            )}
            {others.length > 0 && (
              <FormControl component="fieldset">
                <FormLabel component="legend">Après ces projets</FormLabel>
                <FormGroup>
                  {others.map((other) => (
                    <FormControlLabel
                      key={other.id}
                      control={
                        <Checkbox
                          checked={values.dependsOn.includes(other.id)}
                          onChange={(event) => toggleDependency(other.id, event.target.checked)}
                        />
                      }
                      label={other.name}
                    />
                  ))}
                </FormGroup>
              </FormControl>
            )}
            <TextField
              label="Note"
              multiline
              minRows={2}
              value={values.note}
              onChange={(event) => set('note', event.target.value)}
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
