import { useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import {
  Confirm,
  FormDataConsumer,
  SaveButton,
  SelectInput,
  SimpleForm,
  TextInput,
  Toolbar,
  required,
  useCreate,
  useDelete,
  useGetOne,
  useNotify,
  useUpdate,
} from 'react-admin'
import type { RaRecord } from 'react-admin'
import { Placeholder } from '../../components/list/ListEmpty'
import { formatCents } from './accountTypes'
import { ONE_OFF, formatPeriod, formatSeason } from './budgetModes'
import { AlertThresholdInput, AmountInput, SeasonsInput } from './EnvelopeForm'
import { EnvelopeSplitSlider } from './EnvelopeSplitSlider'
import { categoryLabel } from './categoryTree'
import type { CategoryNode } from './categoryTree'
import {
  bareId,
  categoryIri,
  daughtersOf,
  envelopeIri,
  envelopePayload,
  findLine,
  monthlyLineOf,
  selectionOfEnvelope,
} from './envelopeTree'
import type { Selection } from './envelopeTree'
import type { BudgetLine, BudgetStatus, OneOffBudgetLine } from './useBudgetStatus'

type Values = Record<string, unknown>

/** An envelope of a category is monthly or annual; a one-off one is another matter altogether. */
const TYPE_CHOICES = [
  { id: 'monthly', name: 'Mensuelle' },
  { id: 'annual', name: 'Annuelle' },
]

const errorMessage = (error: unknown): string =>
  error instanceof Error && error.message ? error.message : "L'enveloppe n'a pas pu être enregistrée."

const EditorToolbar = ({ alwaysEnable, onDelete }: { alwaysEnable?: boolean; onDelete?: () => void }) => (
  <Toolbar sx={{ display: 'flex', justifyContent: 'space-between' }}>
    <SaveButton label="Enregistrer" alwaysEnable={alwaysEnable} />
    {onDelete && (
      <Button color="error" startIcon={<DeleteIcon />} onClick={onDelete}>
        Supprimer l&apos;enveloppe
      </Button>
    )}
  </Toolbar>
)

const EnvelopeFields = ({ oneOff }: { oneOff: boolean }) =>
  oneOff ? (
    <>
      <TextInput
        source="name"
        label="Nom de l'événement"
        validate={required()}
        fullWidth
        helperText="Hellfest 2026, Vacances en Bretagne…"
      />
      <AmountInput />
    </>
  ) : (
    <>
      <SelectInput source="mode" label="Type d'enveloppe" choices={TYPE_CHOICES} validate={required()} fullWidth />
      <AmountInput helperText="Ce que vous vous autorisez à dépenser sur toute la période." />
      <AlertThresholdInput />
      <FormDataConsumer>{({ formData }) => formData.mode === 'monthly' && <SeasonsInput />}</FormDataConsumer>
    </>
  )

const formSx = { maxWidth: 560, '& .MuiFormControl-root': { width: '100%' } }

interface FormProps {
  year: number
  month: number
  onSaved: (selection: Selection) => void
}

/** The envelope a category does not have yet: type, amount, threshold, seasons. */
const CreateEnvelopeForm = ({
  category,
  oneOff,
  year,
  month,
  onSaved,
}: FormProps & { category?: CategoryNode; oneOff?: boolean }) => {
  const [create] = useCreate()
  const notify = useNotify()
  const [error, setError] = useState<string | null>(null)

  const initial = useMemo<Values>(
    () =>
      oneOff
        ? { mode: ONE_OFF, currency: 'EUR' }
        : { mode: 'monthly', currency: 'EUR', category: categoryIri(category?.id ?? ''), seasonalAmounts: [] },
    [oneOff, category?.id],
  )

  const onSubmit = async (values: Values) => {
    setError(null)
    try {
      const saved = await create(
        'envelopes',
        { data: envelopePayload({ ...initial, ...values }, year, month) },
        { returnPromise: true },
      )
      notify('Enveloppe créée', { type: 'success' })
      onSaved(selectionOfEnvelope(saved as RaRecord & { mode?: string; category?: string }))
    } catch (e) {
      // The form stays as it is: the owner corrects it and tries again.
      setError(errorMessage(e))
      notify(errorMessage(e), { type: 'error' })
    }
  }

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2, maxWidth: 560 }}>
          {error}
        </Alert>
      )}
      <Box data-testid="envelope-form">
      <SimpleForm
        record={initial}
        onSubmit={onSubmit}
        toolbar={<EditorToolbar alwaysEnable />}
        sx={formSx}
      >
        <EnvelopeFields oneOff={Boolean(oneOff)} />
      </SimpleForm>
      </Box>
    </>
  )
}

/** « 150,00 € répartis aux sous-catégories · 150,00 € libres » when the envelope is a mother. */
const Allocation = ({ line }: { line: BudgetLine }) => {
  if (line.freeCents == null && line.allocatedToChildrenCents == null) return null

  return (
    <Typography variant="body2" color="text.secondary" data-testid="envelope-allocation">
      {line.allocatedToChildrenCents != null &&
        `${formatCents(line.allocatedToChildrenCents, line.currency)} répartis aux sous-catégories`}
      {line.freeCents != null && ` · ${formatCents(line.freeCents, line.currency)} libres`}
    </Typography>
  )
}

interface EditProps extends FormProps {
  line: BudgetLine | OneOffBudgetLine
  status: BudgetStatus | null
  onDeleted: (line: BudgetLine | OneOffBudgetLine) => void
  onSplitSaved: () => void
}

const EditEnvelopeForm = ({ line, status, year, month, onSaved, onDeleted, onSplitSaved }: EditProps) => {
  const oneOff = line.mode === ONE_OFF
  const { data: record, isPending, error: loadError } = useGetOne('envelopes', { id: envelopeIri(line.id) })
  const [update] = useUpdate()
  const [deleteOne, { isPending: deleting }] = useDelete()
  const notify = useNotify()
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)

  const daughters = !oneOff ? daughtersOf(status, line as BudgetLine) : []
  const title = oneOff ? (line as OneOffBudgetLine).name : (line as BudgetLine).categoryPath
  const season = !oneOff ? (line as BudgetLine).season : null

  const onSubmit = async (values: Values) => {
    setError(null)
    try {
      const saved = await update(
        'envelopes',
        { id: values.id as string, data: envelopePayload(values, year, month), previousData: record },
        { returnPromise: true },
      )
      notify('Enveloppe enregistrée', { type: 'success' })
      onSaved(selectionOfEnvelope(saved as RaRecord & { mode?: string; category?: string }))
    } catch (e) {
      setError(errorMessage(e))
      notify(errorMessage(e), { type: 'error' })
    }
  }

  const onConfirmDelete = () => {
    deleteOne(
      'envelopes',
      { id: envelopeIri(line.id), previousData: record },
      {
        mutationMode: 'pessimistic',
        onSuccess: () => {
          setConfirming(false)
          notify('Enveloppe supprimée', { type: 'success' })
          onDeleted(line)
        },
        onError: (e) => {
          setConfirming(false)
          setError(errorMessage(e))
          notify(errorMessage(e), { type: 'error' })
        },
      },
    )
  }

  return (
    <>
      <Box sx={{ mb: 2 }} data-testid="envelope-summary">
        <Typography variant="h6">{title}</Typography>
        <Typography variant="body2" color="text.secondary">
          {formatPeriod(line.mode, (line as BudgetLine).year, (line as BudgetLine).month)}
          {season && ` · ${formatSeason(season)} : ${formatCents(season.amountCents, line.currency)}`}
        </Typography>
        <Typography variant="body2">
          {formatCents(line.consumedCents, line.currency)} consommés sur {formatCents(line.amountCents, line.currency)}
        </Typography>
        {!oneOff && <Allocation line={line as BudgetLine} />}
      </Box>

      {daughters.length > 0 && (
        <Box sx={{ mb: 2 }} data-testid="envelope-ruler">
          <Typography variant="subtitle2" component="h3" sx={{ fontWeight: 600 }}>
            Répartir
          </Typography>
          <Typography variant="caption" color="text.secondary" component="p">
            Partagez le montant entre les sous-catégories : le reste demeure libre.
          </Typography>
          <EnvelopeSplitSlider
            key={`${line.amountCents}:${daughters.map((d) => d.amountCents).join(',')}`}
            mother={line as BudgetLine}
            daughters={daughters}
            onSaved={onSplitSaved}
          />
        </Box>
      )}

      {error && (
        <Alert severity="error" sx={{ mb: 2, maxWidth: 560 }}>
          {error}
        </Alert>
      )}
      {loadError && <Alert severity="error">Impossible de charger l&apos;enveloppe.</Alert>}
      {isPending && !loadError && <LinearProgress />}
      {record && (
        <Box data-testid="envelope-form">
          <SimpleForm
            record={record}
            onSubmit={onSubmit}
            toolbar={<EditorToolbar onDelete={() => setConfirming(true)} />}
            sx={formSx}
          >
            <EnvelopeFields oneOff={oneOff} />
          </SimpleForm>
        </Box>
      )}

      <Confirm
        isOpen={confirming}
        loading={deleting}
        title={`Supprimer l'enveloppe « ${title} » ?`}
        content="Les dépenses restent en place : seul le plafond disparaît. Cette action est définitive."
        onConfirm={onConfirmDelete}
        onClose={() => setConfirming(false)}
      />
    </>
  )
}

export interface EnvelopeNodePaneProps {
  selection: Selection | null
  status: BudgetStatus | null
  loading: boolean
  categories: Array<CategoryNode>
  year: number
  month: number
  /** An envelope was created or saved: the screen refreshes the status and follows it. */
  onSaved: (selection: Selection) => void
  /** An envelope was deleted. */
  onDeleted: (line: BudgetLine | OneOffBudgetLine) => void
  onSplitSaved: () => void
}

const hasNoEnvelope = (status: BudgetStatus | null): boolean =>
  status != null &&
  status.budgets.length === 0 &&
  (status.outsideMonthlyBudget?.annual.length ?? 0) === 0 &&
  (status.outsideMonthlyBudget?.oneOff.length ?? 0) === 0

/** The right pane: the editor of the node selected in the tree. */
export const EnvelopeNodePane = ({
  selection,
  status,
  loading,
  categories,
  year,
  month,
  onSaved,
  onDeleted,
  onSplitSaved,
}: EnvelopeNodePaneProps) => {
  const body = () => {
    if (!selection) {
      return hasNoEnvelope(status) ? (
        <Placeholder
          title="Aucune enveloppe pour l'instant"
          description="Une enveloppe fixe ce que vous vous autorisez sur une catégorie, et les dépenses s'en déduisent au fil du mois. Choisissez une catégorie dans l'arbre pour lui en donner une, ou créez une enveloppe ponctuelle pour un événement."
        />
      ) : (
        <Placeholder
          title="Choisissez une catégorie"
          description="Sélectionnez une catégorie de l'arbre pour voir, créer ou modifier son enveloppe."
        />
      )
    }

    if (selection.kind === 'new-one-off') {
      return (
        <>
          <Typography variant="h6" sx={{ mb: 0.5 }}>
            Nouvelle enveloppe ponctuelle
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Un événement (Hellfest 2026, des vacances) : ni catégorie ni période. Vous y attribuez les dépenses à la
            main depuis la transaction.
          </Typography>
          <CreateEnvelopeForm key="new-one-off" oneOff year={year} month={month} onSaved={onSaved} />
        </>
      )
    }

    const line =
      selection.kind === 'envelope'
        ? findLine(status, selection.envelopeId)
        : monthlyLineOf(status, selection.categoryId)

    if (line) {
      return (
        <EditEnvelopeForm
          key={line.id}
          line={line}
          status={status}
          year={year}
          month={month}
          onSaved={onSaved}
          onDeleted={onDeleted}
          onSplitSaved={onSplitSaved}
        />
      )
    }

    if (selection.kind === 'envelope') {
      return loading || !status ? (
        <LinearProgress />
      ) : (
        <Placeholder
          title="Enveloppe introuvable"
          description="Elle n'existe pas sur cette période : choisissez-en une autre dans l'arbre."
        />
      )
    }

    const category = categories.find((candidate) => bareId(candidate.id) === bareId(selection.categoryId))
    if (!category) return <LinearProgress />

    return (
      <>
        <Typography variant="h6">{categoryLabel(category)}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }} data-testid="envelope-none">
          Sans enveloppe en {formatPeriod('monthly', year, month)}. Une enveloppe plafonne les dépenses de la catégorie
          sur la période ; une annuelle couvre toute l&apos;année {year}, à part du budget du mois.
        </Typography>
        <Typography variant="subtitle2" component="h3" sx={{ fontWeight: 600, mb: 1 }}>
          Créer une enveloppe
        </Typography>
        <CreateEnvelopeForm
          key={`${category.id}:${year}:${month}`}
          category={category}
          year={year}
          month={month}
          onSaved={onSaved}
        />
      </>
    )
  }

  return (
    <Stack data-testid="envelope-editor" sx={{ minWidth: 0 }}>
      {body()}
    </Stack>
  )
}
