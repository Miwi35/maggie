import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { useCreate, useGetList, useUpdate } from 'react-admin'
import { signedAmountCents, natureOfAmount } from './transactionStatuses'
import {
  DAY_RULE_CHOICES,
  DEFAULT_AMOUNT_TOLERANCE_PERCENT,
  DEFAULT_DATE_TOLERANCE_DAYS,
  idOf,
  PERIOD_CHOICES,
} from './recurringOperations'
import type { RaRecord } from 'react-admin'
import type { RecurringPreviewCriteria } from './useRecurringOperationPreview'
import type { TransactionNature } from './transactionStatuses'
import type { DayRule, RecurrencePeriod } from './recurringOperations'
import { RecurringMatchesPanel } from './RecurringMatchesPanel'
import { Qualification, RequalifiedNotice } from './Qualification'
import { NO_QUALIFICATION, senseOfAmount } from './qualificationKinds'
import type { QualificationValue } from './qualificationKinds'

/** What the form opens with: a series being edited, or a transaction being generalised. */
export interface RecurringFormValues {
  label: string
  counterpartyName: string
  labelPattern: string
  /** Signed cents: negative is a dépense. */
  referenceAmountCents: number | null
  account: string
  category: string
  /** Whether the lines are mandatory and what kind of movement they are; copied onto each of them. */
  qualification: QualificationValue
  period: RecurrencePeriod
  dayRule: DayRule
  anchorOn: string
  amountTolerancePercent: number
  dateToleranceDays: number
  endsOn: string
}

export const emptyRecurringValues = (): RecurringFormValues => ({
  label: '',
  counterpartyName: '',
  labelPattern: '',
  referenceAmountCents: null,
  account: '',
  category: '',
  qualification: NO_QUALIFICATION,
  period: 'monthly',
  dayRule: 'fixed_day',
  anchorOn: '',
  amountTolerancePercent: DEFAULT_AMOUNT_TOLERANCE_PERCENT,
  dateToleranceDays: DEFAULT_DATE_TOLERANCE_DAYS,
  endsOn: '',
})

interface RecurringOperationDialogProps {
  /** The series' IRI when editing; absent when creating. */
  recordId?: string
  initial?: Partial<RecurringFormValues>
  onClose: () => void
  onSaved: (saved: RaRecord) => void
}

const toEuros = (cents: number | null) => (cents === null ? '' : String(Math.abs(cents) / 100))

export const RecurringOperationDialog = ({ recordId, initial, onClose, onSaved }: RecurringOperationDialogProps) => {
  const editing = recordId !== undefined
  const [values, setValues] = useState<RecurringFormValues>({ ...emptyRecurringValues(), ...initial })
  const [nature, setNature] = useState<TransactionNature>(natureOfAmount(initial?.referenceAmountCents))
  const [amount, setAmount] = useState(toEuros(initial?.referenceAmountCents ?? null))
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const { data: accounts = [] } = useGetList<RaRecord & { name: string }>('accounts', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })
  // Attached lines that saving will requalify: those a series set, never those qualified by hand.
  const { total: attachedCount } = useGetList(
    'transactions',
    { pagination: { page: 1, perPage: 1 }, filter: { qualifiedBySeries: recordId } },
    { enabled: editing },
  )
  const [create, { isPending: creating }] = useCreate()
  const [update, { isPending: updating }] = useUpdate()
  const saving = creating || updating

  const qualificationChanged =
    values.category !== (initial?.category ?? '') ||
    values.qualification.mandatory !== (initial?.qualification ?? NO_QUALIFICATION).mandatory ||
    values.qualification.kind !== (initial?.qualification ?? NO_QUALIFICATION).kind
  const requalified = editing && qualificationChanged ? (attachedCount ?? null) : null

  const set = <K extends keyof RecurringFormValues>(key: K, value: RecurringFormValues[K]) =>
    setValues((v) => ({ ...v, [key]: value }))

  const cents = Math.round(Math.abs(parseFloat(amount)) * 100)
  const problems = {
    label: values.label.trim() === '' ? 'Donnez un nom à la série' : null,
    amount: !Number.isFinite(cents) || cents === 0 ? 'Saisissez un montant supérieur à 0' : null,
    account: values.account === '' ? 'Choisissez un compte' : null,
    category: values.category === '' ? 'Choisissez une catégorie' : null,
    anchorOn: values.anchorOn === '' ? 'Indiquez la date d’une échéance' : null,
  }
  const previewCriteria: RecurringPreviewCriteria = {
    referenceAmountCents: Number.isFinite(cents) && cents > 0 ? signedAmountCents(cents, nature) : null,
    amountTolerancePercent: values.amountTolerancePercent,
    counterpartyName: values.counterpartyName,
    labelPattern: values.labelPattern,
    accountId: values.account === '' ? '' : idOf(values.account),
  }
  const invalid = Object.values(problems).some((p) => p !== null)

  const submit = () => {
    setTouched(true)
    setError(null)
    if (invalid) return

    const referenceAmountCents = signedAmountCents(cents, nature)
    const data = {
      label: values.label.trim(),
      counterpartyName: values.counterpartyName.trim() === '' ? null : values.counterpartyName.trim(),
      labelPattern: values.labelPattern.trim() === '' ? null : values.labelPattern.trim(),
      account: values.account,
      category: values.category,
      qualification: values.qualification,
      period: values.period,
      dayRule: values.period === 'weekly' ? 'fixed_day' : values.dayRule,
      anchorOn: values.anchorOn,
      referenceAmountCents,
      ...(referenceAmountCents === (initial?.referenceAmountCents ?? null) ? {} : { referenceSource: 'declared' }),
      amountTolerancePercent: values.amountTolerancePercent,
      dateToleranceDays: values.dateToleranceDays,
      endsOn: values.endsOn === '' ? null : values.endsOn,
    }
    const options = {
      onSuccess: (saved: RaRecord) => onSaved(saved),
      onError: (e: unknown) =>
        setError(e instanceof Error && e.message ? e.message : 'Impossible d’enregistrer l’opération récurrente'),
    }

    if (editing) {
      update('recurring_operations', { id: recordId, data, previousData: { id: recordId } }, options)
    } else {
      create('recurring_operations', { data }, options)
    }
  }

  const shown = (key: keyof typeof problems) => (touched ? problems[key] : null)

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="recurring-dialog-title">
      <DialogTitle id="recurring-dialog-title">
        {editing ? 'Modifier l’opération récurrente' : 'Nouvelle opération récurrente'}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <ToggleButtonGroup
            exclusive
            fullWidth
            color="primary"
            value={nature}
            onChange={(_, next: TransactionNature | null) => next && setNature(next)}
            aria-label="Nature de l'opération récurrente"
          >
            <ToggleButton value="expense">Dépense</ToggleButton>
            <ToggleButton value="income">Recette</ToggleButton>
          </ToggleButtonGroup>

          <TextField
            label="Libellé"
            value={values.label}
            onChange={(e) => set('label', e.target.value)}
            error={Boolean(shown('label'))}
            helperText={shown('label') ?? 'Le nom que vous lirez dans la liste — « Abonnement Flixo », « Loyer ».'}
            required
            fullWidth
          />
          <TextField
            label="Contrepartie attendue"
            value={values.counterpartyName}
            onChange={(e) => set('counterpartyName', e.target.value)}
            helperText="Facultatif. Qui est payé (ou qui paie), tel que la banque le nomme : c’est ce qui rattache les transactions."
            fullWidth
          />
          <TextField
            label="Motif du libellé"
            value={values.labelPattern}
            onChange={(e) => set('labelPattern', e.target.value)}
            helperText="Facultatif. Reconnaît les transactions dont le libellé contient… (si la banque ne nomme pas la contrepartie)."
            fullWidth
          />
          <Typography variant="caption" color="text.secondary">
            Sans contrepartie ni libellé, la série est reconnue par son montant ± tolérance et son sens.
          </Typography>
          <TextField
            label="Montant de référence (€)"
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            error={Boolean(shown('amount'))}
            helperText={shown('amount') ?? 'Toujours positif : le choix Dépense / Recette donne le sens.'}
            slotProps={{ htmlInput: { min: 0, step: 0.01 } }}
            required
            fullWidth
          />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              label="Compte"
              value={values.account}
              onChange={(e) => set('account', e.target.value)}
              error={Boolean(shown('account'))}
              helperText={shown('account')}
              required
              fullWidth
            >
              {accounts.map((a) => (
                <MenuItem key={a.id} value={String(a.id)}>
                  {a.name}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <Qualification
            required
            category={values.category === '' ? null : values.category}
            qualification={values.qualification}
            sense={senseOfAmount(signedAmountCents(cents, nature))}
            error={shown('category')}
            onChange={(next) => setValues((v) => ({ ...v, category: next.category ?? '', qualification: next.qualification }))}
            categoryHelperText="Toutes les catégories sont proposées : le sens vient du montant."
          />
          <RequalifiedNotice count={requalified} />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              label="Périodicité"
              value={values.period}
              onChange={(e) => set('period', e.target.value as RecurrencePeriod)}
              fullWidth
            >
              {PERIOD_CHOICES.map((p) => (
                <MenuItem key={p.id} value={p.id}>
                  {p.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label="Jour attendu"
              value={values.period === 'weekly' ? 'fixed_day' : values.dayRule}
              onChange={(e) => set('dayRule', e.target.value as DayRule)}
              disabled={values.period === 'weekly'}
              fullWidth
            >
              {DAY_RULE_CHOICES.map((r) => (
                <MenuItem key={r.id} value={r.id}>
                  {r.name}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Date d’une échéance"
              type="date"
              value={values.anchorOn}
              onChange={(e) => set('anchorOn', e.target.value)}
              error={Boolean(shown('anchorOn'))}
              helperText={shown('anchorOn') ?? 'La série se compte à partir d’elle.'}
              slotProps={{ inputLabel: { shrink: true } }}
              required
              fullWidth
            />
            <TextField
              label="Dernière échéance"
              type="date"
              value={values.endsOn}
              onChange={(e) => set('endsOn', e.target.value)}
              helperText="Facultatif : laissez vide tant que la série continue."
              slotProps={{ inputLabel: { shrink: true } }}
              fullWidth
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Tolérance sur le montant (%)"
              type="number"
              value={values.amountTolerancePercent}
              onChange={(e) => set('amountTolerancePercent', Number(e.target.value))}
              slotProps={{ htmlInput: { min: 0, max: 100 } }}
              helperText="Au-delà, la transaction est proposée, pas rattachée."
              fullWidth
            />
            <TextField
              label="Tolérance sur la date (jours)"
              type="number"
              value={values.dateToleranceDays}
              onChange={(e) => set('dateToleranceDays', Number(e.target.value))}
              slotProps={{ htmlInput: { min: 0, max: 31 } }}
              helperText="Écart accepté autour de l’échéance."
              fullWidth
            />
          </Stack>

          <RecurringMatchesPanel criteria={previewCriteria} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button variant="contained" onClick={submit} disabled={saving}>
          {editing ? 'Enregistrer' : 'Créer la série'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
