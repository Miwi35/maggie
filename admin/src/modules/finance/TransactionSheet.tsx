import { useState } from 'react'
import type { ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Typography from '@mui/material/Typography'
import {
  DeleteButton,
  Edit,
  Form,
  SaveButton,
  SelectInput,
  TextInput,
  Toolbar,
  maxLength,
  useGetList,
  useGetOne,
  useNotify,
  useRecordContext,
} from 'react-admin'
import { useWatch } from 'react-hook-form'
import type { RaRecord } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { ONE_OFF } from './budgetModes'
import { useMercure } from '../../hooks/useMercure'
import { CreateFilterDialog } from './CreateFilterDialog'
import { NatureSection } from './NatureSection'
import { RecurrenceSection } from './RecurrenceSection'
import { QualificationInput, QualificationProvenance } from './Qualification'
import { provenanceSentence } from './qualificationKinds'
import {
  ExceptionalInput,
  StatusInput,
  TransactionHeaderFields,
  toTransactionPayload,
} from './TransactionForm'
import { natureOfAmount } from './transactionStatuses'
import { TransactionShareSection } from './TransactionShareSection'
import { useTransactionTransfer } from './useTransactionTransfer'

const TRANSACTION_TOPICS = ['/api/transactions/{id}']

/** The slots of the sheet, in the order they are drawn: later lots fill a slot, none moves another. */
export const TRANSACTION_SHEET_SECTIONS = ['nature', 'classement', 'recurrence', 'partage', 'jugement', 'note'] as const

export type TransactionSheetSection = (typeof TRANSACTION_SHEET_SECTIONS)[number]

export const TRANSACTION_SHEET_TITLES: Record<TransactionSheetSection, string> = {
  nature: 'Nature',
  classement: 'Classement',
  recurrence: 'Récurrence',
  partage: 'Partage',
  jugement: 'Jugement',
  note: 'Note',
}

/** A transfer or a rejection counts in no figure: nothing is left to classify, repeat, share or judge. */
const SECTIONS_OF_A_MARKED_LINE: TransactionSheetSection[] = ['nature', 'note']

export const NOTE_MAX_LENGTH = 2000

/**
 * What « Enregistrer » sends: the typed fields. The series is left out on
 * purpose — attaching and detaching have already been written, and the
 * record this form opened on is older than they are.
 */
export const toSheetPayload = (data: Record<string, unknown>) => {
  const payload: Record<string, unknown> = { ...toTransactionPayload(data) }
  delete payload.recurringOperation
  delete payload.recurringOccurrenceOn
  const note = payload.note
  payload.note = typeof note === 'string' && note.trim() !== '' ? note : null
  // « Aucune » is the empty choice: the spend goes back to its category's envelopes.
  if (payload.oneOffEnvelope === '') payload.oneOffEnvelope = null

  return payload
}

const Section = ({ name, children }: { name: TransactionSheetSection; children: ReactNode }) => (
  <Box component="section" data-section={name} aria-labelledby={`sheet-${name}`} sx={{ width: '100%', mt: 3 }}>
    <Divider sx={{ mb: 2 }} />
    <Typography id={`sheet-${name}`} variant="subtitle1" component="h2" sx={{ fontWeight: 600, mb: 1 }}>
      {TRANSACTION_SHEET_TITLES[name]}
    </Typography>
    {children}
  </Box>
)

const ComingSoon = ({ children }: { children: string }) => (
  <Typography variant="body2" color="text.secondary">
    {children}
  </Typography>
)

/**
 * Attributes the spend to an event (Hellfest 2026): it keeps its category but
 * leaves the monthly and annual envelopes. Saved with « Enregistrer ».
 */
export const OneOffEnvelopeInput = () => {
  const current = useWatch({ name: 'oneOffEnvelope' }) as string | null | undefined
  // Filtered by the API: the one-off envelopes have no year, and would sort
  // after a hundred monthly ones on a single page.
  const { data: envelopes = [] } = useGetList('envelopes', {
    pagination: { page: 1, perPage: 100 },
    filter: { mode: ONE_OFF },
  })
  // The one the line already carries stays offered, even beyond the first page.
  const choices = envelopes
    .filter((e) => e.mode === ONE_OFF || e.id === current)
    .map((e) => ({ id: e.id, name: (e.name as string | undefined) ?? String(e.id) }))
  if (current && !choices.some((c) => c.id === current)) {
    choices.push({ id: current, name: 'Enveloppe ponctuelle' })
  }

  return (
    <SelectInput
      source="oneOffEnvelope"
      label="Attribuer à une enveloppe ponctuelle"
      choices={choices}
      emptyText="Aucune"
      fullWidth
      helperText={
        choices.length === 0
          ? 'Aucune enveloppe ponctuelle : créez-en une (Hellfest, vacances…) dans les enveloppes.'
          : 'La dépense garde sa catégorie mais sort des enveloppes du mois et de l’année.'
      }
    />
  )
}

/** Who qualified the line: the series or the rule that set it, or the owner. */
const QualificationOrigin = ({ record }: { record: RaRecord }) => {
  const by = record.qualifiedBy as string | null | undefined
  const seriesId = (record.qualifiedBySeries as string | null | undefined) || null
  const ruleId = (record.qualifiedByRule as string | null | undefined) || null
  const { data: series } = useGetOne('recurring_operations', { id: seriesId ?? '' }, { enabled: by === 'series' && seriesId !== null })
  const { data: rule } = useGetOne('categorization_rules', { id: ruleId ?? '' }, { enabled: by === 'rule' && ruleId !== null })

  return (
    <QualificationProvenance
      sentence={provenanceSentence(by, series?.label as string | undefined, rule?.labelPattern as string | undefined)}
    />
  )
}

const ClassementSection = ({ record }: { record: RaRecord }) => {
  const [filtering, setFiltering] = useState(false)
  const nature = (useWatch({ name: 'nature' }) as string | undefined) ?? 'expense'

  return (
    <Box>
      <QualificationInput sense={nature === 'income' ? 'in' : 'out'} categoryHelperText="Facultatif. Choisir ici, c’est qualifier la ligne à la main : ni une série ni une règle n’y touchera plus." />
      <QualificationOrigin record={record} />
      <OneOffEnvelopeInput />
      <StatusInput />
      <ExceptionalInput />
      <Button onClick={() => setFiltering(true)}>Créer un filtre</Button>
      {filtering && <CreateFilterDialog record={record} onClose={() => setFiltering(false)} />}
    </Box>
  )
}

const SheetBody = ({ saveError }: { saveError: string | null }) => {
  const record = useRecordContext()
  const transferApi = useTransactionTransfer(record?.id as string | undefined)

  // The other leg can be marked or released from another screen: stay in step.
  useMercure(TRANSACTION_TOPICS, () => {
    void transferApi.load()
  })

  if (!record) return null

  const kind = transferApi.transfer?.transferKind ?? (record.transferKind as string | undefined) ?? 'none'
  const marked = kind !== 'none'
  const amountCents = record.amountCents as number

  const content: Record<TransactionSheetSection, ReactNode> = {
    nature: <NatureSection transferApi={transferApi} kind={kind} amountCents={amountCents} />,
    classement: <ClassementSection record={record} />,
    recurrence: <RecurrenceSection record={record} />,
    partage: <TransactionShareSection transactionId={String(record.id)} label={String(record.label ?? '')} />,
    jugement: <ComingSoon>Bientôt : dire si cette dépense était un bon choix.</ComingSoon>,
    note: (
      <TextInput
        source="note"
        label="Note"
        multiline
        minRows={3}
        fullWidth
        validate={maxLength(NOTE_MAX_LENGTH, `La note ne peut pas dépasser ${NOTE_MAX_LENGTH} caractères`)}
        helperText={`Ce que vous voulez retenir de cette ligne. ${NOTE_MAX_LENGTH} caractères au plus.`}
      />
    ),
  }

  return (
    <Box sx={{ maxWidth: 680, width: '100%' }}>
      <FormSection first title="La transaction" description="Le compte, le libellé, le montant et la date de la ligne." />
      <TransactionHeaderFields withCurrency />

      {TRANSACTION_SHEET_SECTIONS.filter((name) => !marked || SECTIONS_OF_A_MARKED_LINE.includes(name)).map((name) => (
        <Section key={name} name={name}>
          {content[name]}
        </Section>
      ))}

      {saveError && (
        <Alert severity="error" sx={{ mt: 2 }}>
          {saveError}
        </Alert>
      )}
      <Toolbar sx={{ justifyContent: 'space-between', mt: 2, px: 0 }}>
        <SaveButton />
        <DeleteButton />
      </Toolbar>
    </Box>
  )
}

/**
 * The one sheet of a transaction, in six ordered sections: its nature, how it
 * is classified, whether it repeats, whether it is shared, how it is judged,
 * and the owner's note. The nature decides the rest — a transfer or a
 * rejection keeps only Nature and Note.
 *
 * Saving is mixed: the gestures with an effect of their own (marking, taking a
 * marking off, attaching, detaching, creating a filter) act at once; the typed
 * fields go with « Enregistrer ».
 */
export const TransactionSheet = () => {
  const notify = useNotify()
  const [saveError, setSaveError] = useState<string | null>(null)

  return (
    <Edit
      title="Fiche de la transaction"
      transform={toSheetPayload}
      mutationMode="pessimistic"
      mutationOptions={{
        onError: (error: unknown) => {
          const message = error instanceof Error && error.message ? error.message : 'L’enregistrement a échoué'
          setSaveError(message)
          notify(message, { type: 'error' })
        },
      }}
    >
      <Form
        // A line opens as what its sign says.
        defaultValues={(record?: RaRecord) => ({ nature: natureOfAmount(record?.amountCents as number | undefined) })}
      >
        <SheetBody saveError={saveError} />
      </Form>
    </Edit>
  )
}
