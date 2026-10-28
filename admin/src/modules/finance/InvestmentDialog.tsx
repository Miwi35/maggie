import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { borrowedCents, loanFieldsOf, toInvestmentInput, validateInvestment, valuesOf } from './investmentForm'
import type { InvestmentFormErrors, InvestmentFormField, InvestmentFormValues } from './investmentForm'
import { fetchLoanConditions } from './projectsApi'
import type { InvestmentInput, Project } from './projectsApi'

const failureText = (error: unknown) =>
  error instanceof Error && error.message ? error.message : "L'investissement n'a pas été enregistré."

interface InvestmentDialogProps {
  project: Project
  /** Every project: the resale may be set aside for any other one. */
  projects: Project[]
  onClose: () => void
  onSubmit: (input: InvestmentInput) => Promise<void>
}

/**
 * Setting a project's investment (MAG-557): the price, the down payment, the rent, the loan for the rest and,
 * optionally, a planned resale with the project its proceeds are set aside for. The API refuses what does not
 * add up, in its own words, shown above the form.
 */
export const InvestmentDialog = ({ project, projects, onClose, onSubmit }: InvestmentDialogProps) => {
  const [values, setValues] = useState<InvestmentFormValues>(() => valuesOf(project))
  const [errors, setErrors] = useState<InvestmentFormErrors>({})
  const [failure, setFailure] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const loanId = project.investment?.loanId ?? null
  // The loan's conditions are read to start the form from them; without a loan, the form asks for them.
  const [conditionsLost, setConditionsLost] = useState(false)

  useEffect(() => {
    if (!loanId) return
    let live = true
    fetchLoanConditions(loanId)
      .then((found) => live && setValues((previous) => ({ ...previous, ...loanFieldsOf(found) })))
      .catch(() => live && setConditionsLost(true))

    return () => {
      live = false
    }
  }, [loanId])

  const set = <K extends keyof InvestmentFormValues>(key: K, value: InvestmentFormValues[K]) =>
    setValues((previous) => ({ ...previous, [key]: value }))

  const borrowed = borrowedCents(values)
  const needsLoan = loanId === null

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const found = validateInvestment(values, needsLoan)
    setErrors(found)
    setFailure(null)
    if (Object.keys(found).length > 0) return

    setSaving(true)
    try {
      await onSubmit(toInvestmentInput(values))
    } catch (error) {
      setFailure(failureText(error))
    } finally {
      setSaving(false)
    }
  }

  const amountField = (key: Extract<InvestmentFormField, 'price' | 'downPayment' | 'rent' | 'resaleNet'>, label: string) => (
    <TextField
      label={label}
      required
      value={values[key]}
      onChange={(event) => set(key, event.target.value)}
      error={Boolean(errors[key])}
      helperText={errors[key]}
      slotProps={{ htmlInput: { inputMode: 'decimal' } }}
      fullWidth
    />
  )

  const targets = projects.filter((candidate) => candidate.id !== project.id)
  const title = project.investment ? `Modifier l'investissement « ${project.name} »` : `Investissement · ${project.name}`

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={submit} noValidate aria-label={title}>
        <DialogTitle>{title}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {failure && <Alert severity="error">{failure}</Alert>}
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              {amountField('price', 'Prix (€)')}
              {amountField('downPayment', 'Apport (€)')}
            </Stack>
            {amountField('rent', 'Loyer mensuel (€)')}

            {borrowed !== null && borrowed > 0 && (
              <>
                <Typography variant="body2" color="text.secondary" data-testid="investment-borrowed">
                  Prêt de {formatCents(borrowed, project.currency)} pour le reste du prix.
                </Typography>
                {conditionsLost && (
                  <Alert severity="warning">Les conditions du prêt n'ont pas pu être lues : laissées vides, elles restent celles enregistrées.</Alert>
                )}
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <TextField
                    label="Taux annuel du prêt (%)"
                    required={needsLoan}
                    value={values.rate}
                    onChange={(event) => set('rate', event.target.value)}
                    error={Boolean(errors.rate)}
                    helperText={errors.rate}
                    slotProps={{ htmlInput: { inputMode: 'decimal' } }}
                    fullWidth
                  />
                  <TextField
                    label="Durée du prêt (mois)"
                    required={needsLoan}
                    value={values.months}
                    onChange={(event) => set('months', event.target.value)}
                    error={Boolean(errors.months)}
                    helperText={errors.months}
                    slotProps={{ htmlInput: { inputMode: 'numeric' } }}
                    fullWidth
                  />
                </Stack>
              </>
            )}

            <FormControlLabel
              control={<Checkbox checked={values.hasResale} onChange={(event) => set('hasResale', event.target.checked)} />}
              label="Revente planifiée"
            />
            {values.hasResale && (
              <Stack spacing={2}>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <TextField
                    label="Mois de la revente"
                    type="month"
                    required
                    value={values.resaleMonth}
                    onChange={(event) => set('resaleMonth', event.target.value)}
                    error={Boolean(errors.resaleMonth)}
                    helperText={errors.resaleMonth}
                    slotProps={{ inputLabel: { shrink: true } }}
                    fullWidth
                  />
                  {amountField('resaleNet', 'Net de la revente (€)')}
                </Stack>
                <TextField
                  select
                  label="Projet financé par la revente"
                  value={values.resaleTarget}
                  onChange={(event) => set('resaleTarget', event.target.value)}
                >
                  <MenuItem value="">Aucun</MenuItem>
                  {targets.map((candidate) => (
                    <MenuItem key={candidate.id} value={candidate.id}>
                      {candidate.name}
                    </MenuItem>
                  ))}
                </TextField>
              </Stack>
            )}
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
