import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import { formatCentsRounded } from './accountTypes'
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

const monthFormatter = new Intl.DateTimeFormat('fr-FR', { month: 'short', year: 'numeric' })

/** « juin 2028 »: this month plus `months`, built from the parts so no timezone moves it. */
const availableLabel = (months: number | null, today = new Date()): string =>
  months === null ? '—' : monthFormatter.format(new Date(today.getFullYear(), today.getMonth() + months, 1))

/** « 434 € × 24 »; « — » when there is no payment (cash). */
const monthlyLabel = (option: ProjectOption, currency: string): string =>
  option.monthlyPaymentCents === 0 ? '—' : `${formatCentsRounded(option.monthlyPaymentCents, currency)} × ${option.durationMonths}`

const impactLabel = (months: number | null): string =>
  months === null ? '—' : months === 0 ? '0 mois' : `${months > 0 ? '+' : '−'} ${Math.abs(months)} mois`

const BAR_AREA = 80

const cellSx = { fontSize: 13, py: 1, px: 1.25, fontVariantNumeric: 'tabular-nums', borderColor: 'divider' } as const
const labelSx = { ...cellSx, color: 'veilleuse.caption' } as const
const headSx = { ...cellSx, fontSize: 12, fontWeight: 500, color: 'veilleuse.caption' } as const

const ROWS: { label: string; text: (option: ProjectOption, currency: string) => string }[] = [
  { label: 'Coût réel total', text: (option, currency) => formatCentsRounded(option.totalCostCents, currency) },
  { label: 'Coût net réel', text: (option, currency) => formatCentsRounded(option.netCostCents, currency) },
  { label: 'Disponible', text: (option) => availableLabel(option.availableInMonths) },
  { label: 'Mensualité', text: monthlyLabel },
  { label: 'Impact sur N', text: (option) => impactLabel(option.nImpactMonths) },
]

/** The three columns and the three bars are drawn alike: no colour or emphasis tells one from the others. */
const OptionsCard = ({ options, currency }: { options: Options; currency: string }) => {
  const maxCost = Math.max(0, ...COLUMNS.map((column) => Math.abs(options[column.key].totalCostCents)))

  return (
    <Box
      component="section"
      aria-label="Options"
      data-testid="project-options"
      sx={{ bgcolor: 'background.paper', borderRadius: 4, p: 2.25, display: 'flex', flexDirection: 'column', gap: 1.5 }}
    >
      <Typography sx={{ fontSize: 15, fontWeight: 500 }}>Options</Typography>
      <Table size="small" aria-label="Options">
        <TableHead>
          <TableRow>
            <TableCell sx={headSx} />
            {COLUMNS.map((column) => (
              <TableCell key={column.key} data-testid="project-option" data-option={column.title} sx={headSx}>
                {column.title}
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {ROWS.map((row) => (
            <TableRow key={row.label}>
              <TableCell sx={labelSx}>{row.label}</TableCell>
              {COLUMNS.map((column) => (
                <TableCell key={column.key} sx={cellSx}>
                  {row.text(options[column.key], currency)}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Box role="img" aria-label="Coût et délai des trois options" sx={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: BAR_AREA }}>
        {COLUMNS.map((column) => (
          <Box
            key={column.key}
            data-testid="option-bar"
            style={{ height: `${maxCost > 0 ? (Math.abs(options[column.key].totalCostCents) / maxCost) * 100 : 0}%` }}
            sx={{ width: 60, borderRadius: 1.5, bgcolor: (theme) => alpha(theme.palette.text.primary, 0.28) }}
          />
        ))}
      </Box>
    </Box>
  )
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

      {options && complete && <OptionsCard options={options} currency={project.currency} />}
    </Stack>
  )
}
