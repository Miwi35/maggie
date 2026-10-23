import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import MenuItem from '@mui/material/MenuItem'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import RemoveIcon from '@mui/icons-material/Remove'
import { Form, NumberInput } from 'react-admin'
import { Controller, useFieldArray, useFormContext, useWatch } from 'react-hook-form'
import { formatCents } from './accountTypes'
import {
  KIND_LABELS,
  STEP_CENTS,
  accountsFor,
  remainder,
  segmentsOf,
  toFundingInputs,
  valuesOfProject,
} from './fundingForm'
import type { FundingValues, Segment, SourceValues } from './fundingForm'
import { LoanTermsFields } from './LoanTermsFields'
import { basisPointsInput } from './loans'
import { fetchPlacements } from './placementsApi'
import type { Placement } from './placementsApi'
import { fetchFundingAccounts, fetchLoanConditions, saveFunding } from './projectsApi'
import type { FundingAccount, FundingKind, Project } from './projectsApi'

const SEGMENT_COLORS: Record<Segment['kind'], string> = {
  savings: 'primary.main',
  capital: 'secondary.main',
  loan: 'info.main',
  resale: 'success.main',
  salary: 'grey.500',
}

const failureText = (error: unknown, fallback: string) => (error instanceof Error && error.message ? error.message : fallback)

/** The sources stacked over the project's amount; what overflows it widens the bar. */
const StackedBar = ({ amountCents, currency, segments }: { amountCents: number; currency: string; segments: Segment[] }) => {
  const total = Math.max(amountCents, segments.reduce((sum, segment) => sum + segment.cents, 0), 1)

  return (
    <Box>
      <Box
        role="img"
        aria-label={`Sources : ${segments.map((segment) => `${segment.label} ${formatCents(segment.cents, currency)}`).join(', ')}`}
        data-testid="funding-bar"
        sx={{ display: 'flex', height: 14, borderRadius: 1, overflow: 'hidden', bgcolor: 'action.hover' }}
      >
        {segments.map((segment) => (
          <Box
            key={segment.key}
            data-testid="funding-segment"
            data-kind={segment.kind}
            sx={{ width: `${(segment.cents / total) * 100}%`, bgcolor: SEGMENT_COLORS[segment.kind] }}
          />
        ))}
      </Box>
      <Stack direction="row" sx={{ gap: 2, flexWrap: 'wrap', mt: 1 }}>
        {segments.map((segment) => (
          <Stack key={segment.key} direction="row" sx={{ gap: 0.75, alignItems: 'center' }}>
            <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: SEGMENT_COLORS[segment.kind] }} />
            <Typography variant="body2">
              {segment.label} {formatCents(segment.cents, currency)}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}

const lower = (text: string) => text.charAt(0).toLowerCase() + text.slice(1)

/** « − 1 000 + »: by hundred euros, or typed. */
const AmountStepper = ({ name, label }: { name: string; label: string }) => (
  <Controller
    name={name}
    render={({ field }) => {
      const cents = Number(field.value) || 0

      return (
        <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5 }}>
          <IconButton size="small" aria-label={`Moins ${lower(label)}`} onClick={() => field.onChange(Math.max(0, cents - STEP_CENTS))}>
            <RemoveIcon fontSize="small" />
          </IconButton>
          <TextField
            size="small"
            type="number"
            label={label}
            value={cents / 100}
            onChange={(event) => field.onChange(Math.max(0, Math.round(Number(event.target.value) * 100)) || 0)}
            slotProps={{ htmlInput: { min: 0, step: 1 } }}
            sx={{ width: 140 }}
          />
          <IconButton size="small" aria-label={`Plus ${lower(label)}`} onClick={() => field.onChange(cents + STEP_CENTS)}>
            <AddIcon fontSize="small" />
          </IconButton>
        </Stack>
      )
    }}
  />
)

interface SourceRowProps {
  index: number
  accounts: FundingAccount[]
  names: Map<string, string>
  currency: string
  placements: Placement[]
  conditionsLost: boolean
  onRemove: () => void
}

/** The resale of an investment (MAG-557) funds the project but is not set here: it follows the investment. */
const ResaleRow = ({ index, names, currency }: { index: number; names: Map<string, string>; currency: string }) => {
  const sourceProject = useWatch({ name: `sources.${index}.sourceProject` }) as string | undefined
  const cents = Number(useWatch({ name: `sources.${index}.amountCents` })) || 0
  const from = sourceProject ? names.get(sourceProject) : undefined

  return (
    <Box data-testid="funding-source" data-kind="resale" sx={{ py: 1.5, borderBottom: 1, borderColor: 'divider' }}>
      <Stack direction="row" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
        <Typography sx={{ fontWeight: 600, minWidth: 90 }}>{KIND_LABELS.resale}</Typography>
        <Typography>{from ? `Revente de ${from}` : "Revente d'un investissement"}</Typography>
        <Typography sx={{ ml: 'auto' }}>{formatCents(cents, currency)}</Typography>
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Elle suit l'investissement : modifiez-la depuis sa carte.
      </Typography>
    </Box>
  )
}

const SourceRow = ({ index, accounts, placements, names, currency, conditionsLost, onRemove }: SourceRowProps) => {
  const kind = useWatch({ name: `sources.${index}.kind` }) as FundingKind
  const choices = accountsFor(kind, accounts)
  const label = KIND_LABELS[kind]

  if (kind === 'resale') return <ResaleRow index={index} names={names} currency={currency} />

  return (
    <Box data-testid="funding-source" data-kind={kind} sx={{ py: 1.5, borderBottom: 1, borderColor: 'divider' }}>
      <Stack direction="row" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
        <Typography sx={{ fontWeight: 600, minWidth: 90 }}>{label}</Typography>
        {kind !== 'loan' && (
          <Controller
            name={`sources.${index}.account`}
            render={({ field }) => (
              <TextField
                select
                size="small"
                label={`Compte (${label.toLowerCase()})`}
                value={field.value ?? ''}
                onChange={(event) => field.onChange(event.target.value)}
                sx={{ minWidth: 200 }}
              >
                {choices.map((account) => (
                  <MenuItem key={account.id} value={account.id}>
                    {account.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />
        )}
        {kind === 'capital' && placements.length > 0 && (
          <Controller
            name={`sources.${index}.placement`}
            render={({ field }) => (
              <TextField
                select
                size="small"
                label="Placement quitté (optionnel)"
                value={field.value ?? ''}
                onChange={(event) => field.onChange(event.target.value)}
                sx={{ minWidth: 200 }}
              >
                <MenuItem value="">Aucun</MenuItem>
                {placements.map((placement) => (
                  <MenuItem key={placement.id} value={placement.id}>
                    {placement.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />
        )}
        <AmountStepper name={`sources.${index}.amountCents`} label={`Montant ${label.toLowerCase()} (€)`} />
        <IconButton size="small" aria-label={`Retirer ${label.toLowerCase()}`} onClick={onRemove} sx={{ ml: 'auto' }}>
          <DeleteOutlineIcon fontSize="small" />
        </IconButton>
      </Stack>
      {kind === 'loan' && conditionsLost && (
        <Alert severity="warning" sx={{ mt: 1 }}>
          Les conditions de l'emprunt n'ont pas pu être lues : elles restent celles enregistrées.
        </Alert>
      )}
      {kind === 'loan' && !conditionsLost && (
        <Box sx={{ mt: 1 }}>
          <NumberInput
            source={`sources.${index}.loan.annualRateBasisPoints`}
            label="Taux annuel (%)"
            {...basisPointsInput}
            sx={{ width: 240 }}
          />
          <LoanTermsFields prefix={`sources.${index}.loan.`} withAmount={false} />
        </Box>
      )}
    </Box>
  )
}

interface FundingFieldsProps {
  project: Project
  accounts: FundingAccount[]
  names: Map<string, string>
  placements: Placement[]
  conditionsLost: boolean
  failure: string | null
  saving: boolean
}

const FundingFields = ({ project, accounts, placements, names, conditionsLost, failure, saving }: FundingFieldsProps) => {
  const { control } = useFormContext<FundingValues>()
  const { fields, append, remove } = useFieldArray({ control, name: 'sources' })
  const sources = (useWatch({ control, name: 'sources' }) ?? []) as SourceValues[]
  const hasLoan = sources.some((source) => source.kind === 'loan')

  const add = (kind: FundingKind) => {
    const first = accountsFor(kind, accounts)[0]
    append({ kind, amountCents: 0, account: first?.id ?? '', ...(kind === 'loan' ? { loan: { type: 'amortizing_fixed' } } : {}) })
  }

  return (
    <Stack spacing={2}>
      {failure && <Alert severity="error">{failure}</Alert>}

      <StackedBar amountCents={project.amountCents} currency={project.currency} segments={segmentsOf(project.amountCents, sources)} />

      <Box>
        {fields.map((field, index) => (
          <SourceRow
            key={field.id}
            index={index}
            accounts={accounts}
            placements={placements}
            names={names}
            currency={project.currency}
            conditionsLost={conditionsLost}
            onRemove={() => remove(index)}
          />
        ))}
        <Stack
          direction="row"
          data-testid="funding-remainder"
          sx={{ justifyContent: 'space-between', alignItems: 'center', py: 1.5 }}
        >
          <Typography sx={{ fontWeight: 600 }}>Le reste</Typography>
          <Typography>
            {formatCents(remainder(project.amountCents, sources), project.currency)} sur salaire
          </Typography>
        </Stack>
      </Box>

      <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap', alignItems: 'center' }}>
        <Button size="small" startIcon={<AddIcon />} onClick={() => add('savings')}>
          Économies
        </Button>
        <Button size="small" startIcon={<AddIcon />} onClick={() => add('capital')}>
          Capital
        </Button>
        <Button size="small" startIcon={<AddIcon />} disabled={hasLoan} onClick={() => add('loan')}>
          Emprunt
        </Button>
        <Button type="submit" variant="contained" disabled={saving} sx={{ ml: 'auto' }}>
          Enregistrer les sources
        </Button>
      </Stack>
    </Stack>
  )
}

interface ProjectFundingPanelProps {
  project: Project
  /** Project names by bare id, to name the investment a resale source comes from. */
  names?: Map<string, string>
  /** The project as the API answered the write. */
  onSaved: (project: Project) => void
}

/**
 * A project's funding sources (MAG-525): savings, capital, a projected loan;
 * « − montant + » by hundred euros or typed, « le reste » on the salary, the
 * sources stacked over the amount. Saving sends the whole list; a refusal is
 * shown as the API words it and nothing is kept.
 */
export const ProjectFundingPanel = ({ project, names = new Map(), onSaved }: ProjectFundingPanelProps) => {
  const loanId = project.fundingSources.find((source) => source.kind === 'loan')?.loanId ?? null
  const [accounts, setAccounts] = useState<FundingAccount[] | null>(null)
  // Optional: a refusal to list them only hides the choice of the placement left (MAG-556).
  const [placements, setPlacements] = useState<Placement[]>([])
  const [conditions, setConditions] = useState<Record<string, unknown> | null | undefined>(loanId ? undefined : null)
  const [failure, setFailure] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let live = true
    fetchFundingAccounts()
      .then((found) => live && setAccounts(found))
      .catch(() => live && setAccounts([]))

    return () => {
      live = false
    }
  }, [])

  useEffect(() => {
    let live = true
    fetchPlacements()
      .then((found) => live && setPlacements(found))
      .catch(() => live && setPlacements([]))

    return () => {
      live = false
    }
  }, [])

  useEffect(() => {
    if (!loanId) return
    let live = true
    fetchLoanConditions(loanId)
      .then((found) => live && setConditions(found))
      .catch(() => live && setConditions(null))

    return () => {
      live = false
    }
  }, [loanId])

  const record = useMemo(() => (conditions === undefined ? null : valuesOfProject(project, conditions)), [project, conditions])

  const submit = async (data: Record<string, unknown>) => {
    setFailure(null)
    setSaving(true)
    try {
      onSaved(await saveFunding(project.id, toFundingInputs(data as unknown as FundingValues)))
    } catch (error) {
      setFailure(failureText(error, "Les sources n'ont pas été enregistrées."))
    } finally {
      setSaving(false)
    }
  }

  if (accounts === null || record === null) return <Skeleton data-testid="funding-loading" height={120} />

  return (
    <Form record={record} onSubmit={submit} sanitizeEmptyValues={false} aria-label={`Sources de ${project.name}`}>
      <FundingFields
        project={project}
        accounts={accounts}
        names={names}
        placements={placements}
        conditionsLost={Boolean(loanId) && conditions === null}
        failure={failure}
        saving={saving}
      />
    </Form>
  )
}
