import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { eurosToCents } from './projectForm'
import { fetchProjectOptions } from './projectsApi'
import type { OptionParams, Project, ProjectOption, ProjectOptions as Options } from './projectsApi'

/** The yield the simulation starts from; the owner changes it. */
const DEFAULT_YIELD_BASIS_POINTS = 300

interface Typed {
  rate: string
  months: string
  downPayment: string
  yield: string
}

const decimal = (basisPoints: number) => String(basisPoints / 100).replace('.', ',')

/** What the project's loan source already says; the rest is typed. */
const initialTyped = (project: Project): Typed => {
  const loan = project.fundingSources.find((source) => source.kind === 'loan')

  return {
    rate: '',
    months: '',
    downPayment: loan ? String((project.amountCents - loan.amountCents) / 100).replace('.', ',') : '',
    yield: decimal(DEFAULT_YIELD_BASIS_POINTS),
  }
}

/** « 4 » or « 4,5 » → 400 or 450 basis points; null when it is not a rate. */
const percentToBasisPoints = (typed: string): number | null => {
  const normalised = typed.trim().replace(',', '.')
  if (!/^\d+(\.\d{1,2})?$/.test(normalised)) return null

  return Math.round(Number(normalised) * 100)
}

const monthsOf = (typed: string): number | null => (/^\d+$/.test(typed.trim()) ? Number(typed.trim()) : null)

const paramsOf = (typed: Typed): OptionParams => ({
  loanRateBasisPoints: percentToBasisPoints(typed.rate),
  loanMonths: monthsOf(typed.months),
  downPaymentCents: eurosToCents(typed.downPayment),
  yieldBasisPoints: percentToBasisPoints(typed.yield),
})

const COLUMNS: { key: 'cash' | 'loan' | 'partial'; title: string }[] = [
  { key: 'cash', title: 'Comptant' },
  { key: 'loan', title: 'Emprunt' },
  { key: 'partial', title: 'Partiel' },
]

/** A bar whose length is `value` on a scale shared by every column. */
const Bar = ({ value, max, label }: { value: number | null; max: number; label: string }) => (
  <Box sx={{ height: 8, borderRadius: 0.5, bgcolor: 'action.hover', overflow: 'hidden' }}>
    <Box
      role="img"
      aria-label={label}
      data-testid="option-bar"
      sx={{ height: '100%', width: `${max > 0 && value !== null ? (Math.min(Math.abs(value), max) / max) * 100 : 0}%`, bgcolor: 'primary.main' }}
    />
  </Box>
)

interface FigureProps {
  title: string
  text: string
  value: number | null
  max: number
}

const Figure = ({ title, text, value, max }: FigureProps) => (
  <Box sx={{ py: 1 }}>
    <Stack direction="row" sx={{ justifyContent: 'space-between', gap: 1, mb: 0.5 }}>
      <Typography variant="body2" color="text.secondary">
        {title}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        {text}
      </Typography>
    </Stack>
    <Bar value={value} max={max} label={`${title} : ${text}`} />
  </Box>
)

const monthsText = (months: number | null) => (months === null ? '—' : months === 0 ? 'Tout de suite' : `${months} mois`)

interface ColumnProps {
  title: string
  option: ProjectOption
  currency: string
  scales: { cost: number; monthly: number; delay: number }
}

/** The same column three times: no colour or emphasis tells one from the others. */
const OptionColumn = ({ title, option, currency, scales }: ColumnProps) => (
  <Paper variant="outlined" data-testid="project-option" data-option={title} sx={{ p: 2, flex: '1 1 0', minWidth: 180 }}>
    <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
      {title}
    </Typography>
    <Figure title="Coût total" text={formatCents(option.totalCostCents, currency)} value={option.totalCostCents} max={scales.cost} />
    <Figure title="Coût net" text={formatCents(option.netCostCents, currency)} value={option.netCostCents} max={scales.cost} />
    <Figure
      title="Mensualité"
      text={formatCents(option.monthlyPaymentCents, currency)}
      value={option.monthlyPaymentCents}
      max={scales.monthly}
    />
    <Figure title="Délai avant jouissance" text={monthsText(option.availableInMonths)} value={option.availableInMonths} max={scales.delay} />
    {option.nImpactMonths !== null && (
      <Typography variant="body2" sx={{ pt: 1 }}>
        Impact sur N : {option.nImpactMonths > 0 ? '+' : ''}
        {option.nImpactMonths} mois
      </Typography>
    )}
  </Paper>
)

const scalesOf = (options: Options) => {
  const all = [options.cash, options.loan, options.partial]
  const maxOf = (values: (number | null)[]) => Math.max(0, ...values.map((value) => Math.abs(value ?? 0)))

  return {
    cost: maxOf(all.flatMap((option) => [option.totalCostCents, option.netCostCents])),
    monthly: maxOf(all.map((option) => option.monthlyPaymentCents)),
    delay: maxOf(all.map((option) => option.availableInMonths)),
  }
}

/**
 * The three ways to pay a project, side by side (MAG-525, rule 11): cash, loan,
 * partial. Facts and bars on one scale, never a verdict. The simulation's
 * parameters are typed; they start from the project's loan source.
 */
export const ProjectOptions = ({ project }: { project: Project }) => {
  const [typed, setTyped] = useState<Typed>(() => initialTyped(project))
  const [options, setOptions] = useState<Options | null>(null)
  const [error, setError] = useState<string | null>(null)
  const params = useMemo(() => paramsOf(typed), [typed])
  // The sources and the amount change the options; the order or the note do not.
  const version = JSON.stringify([project.amountCents, project.fundingSources])
  const loanSource = project.fundingSources.some((source) => source.kind === 'loan')

  const complete =
    params.downPaymentCents !== null &&
    params.yieldBasisPoints !== null &&
    (loanSource || (params.loanRateBasisPoints !== null && params.loanMonths !== null))

  useEffect(() => {
    if (!complete) return
    let live = true
    const timer = setTimeout(() => {
      fetchProjectOptions(project.id, params)
        .then((found) => {
          if (!live) return
          setOptions(found)
          setError(null)
        })
        .catch((failure: unknown) => {
          if (!live) return
          setOptions(null)
          setError(failure instanceof Error ? failure.message : 'Les options ne peuvent pas être chiffrées.')
        })
    }, 300)

    return () => {
      live = false
      clearTimeout(timer)
    }
  }, [project.id, params, complete, version])

  const set = (key: keyof Typed) => (event: { target: { value: string } }) => setTyped((previous) => ({ ...previous, [key]: event.target.value }))

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ gap: 2, flexWrap: 'wrap' }}>
        <TextField size="small" label="Taux de l'emprunt (%)" value={typed.rate} onChange={set('rate')} slotProps={{ htmlInput: { inputMode: 'decimal' } }} sx={{ width: 170 }} />
        <TextField size="small" label="Durée de l'emprunt (mois)" value={typed.months} onChange={set('months')} slotProps={{ htmlInput: { inputMode: 'numeric' } }} sx={{ width: 190 }} />
        <TextField size="small" label="Apport (€)" value={typed.downPayment} onChange={set('downPayment')} slotProps={{ htmlInput: { inputMode: 'decimal' } }} sx={{ width: 140 }} />
        <TextField size="small" label="Rendement (%)" value={typed.yield} onChange={set('yield')} slotProps={{ htmlInput: { inputMode: 'decimal' } }} sx={{ width: 140 }} />
      </Stack>

      {error && <Alert severity="error">{error}</Alert>}

      {options && complete && (
        <Stack direction="row" sx={{ gap: 2, flexWrap: 'wrap' }} data-testid="project-options">
          {COLUMNS.map((column) => (
            <OptionColumn key={column.key} title={column.title} option={options[column.key]} currency={project.currency} scales={scalesOf(options)} />
          ))}
        </Stack>
      )}
    </Stack>
  )
}
