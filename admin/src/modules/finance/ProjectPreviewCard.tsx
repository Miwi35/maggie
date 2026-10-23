import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import type { ProjectPreview } from './projectsApi'
import { impactLabel, nLabel, purchaseLabel } from './projectPreview'

export interface ScenarioChoice {
  id: string
  name: string
  current: boolean
}

interface ProjectPreviewCardProps {
  preview: ProjectPreview
  /** The scenarios the project can be previewed in; the previewed one is added when missing. */
  choices: ScenarioChoice[]
  onScenario: (id: string) => void
  /** True while the preview is recomputed for another scenario. */
  busy?: boolean
}

const NONE = ''

/**
 * « Aperçu · Box à louer » (MAG-553): the dates of the other projects and the date N before and after
 * adding the project, in the scenario the user follows. Facts only, no verdict.
 */
export const ProjectPreviewCard = ({ preview, choices, onScenario, busy = false }: ProjectPreviewCardProps) => {
  const scenario = preview.scenario
  const options =
    scenario && !choices.some((choice) => choice.id === scenario.id)
      ? [{ id: scenario.id, name: scenario.name, current: true }, ...choices]
      : choices

  return (
    <Box component="section" aria-label={`Aperçu · ${preview.project.name}`} data-testid="project-preview" aria-busy={busy}>
      <Stack spacing={2}>
        <Typography variant="h6" component="h3">
          Aperçu · {preview.project.name}
        </Typography>
        <TextField
          select
          size="small"
          label="Scénario"
          value={scenario?.id ?? NONE}
          onChange={(event) => onScenario(event.target.value)}
          disabled={busy || options.length === 0}
        >
          {scenario === null && <MenuItem value={NONE}>Vos projets, dans l’ordre</MenuItem>}
          {options.map((choice) => (
            <MenuItem key={choice.id} value={choice.id}>
              {choice.name}
              {choice.current ? ' (courant)' : ''}
            </MenuItem>
          ))}
        </TextField>
        {scenario && !scenario.editable && (
          <Alert severity="info">
            « {scenario.name} » est une proposition de Maggie : dupliquez-la pour y ajouter un projet, ou choisissez un autre scénario.
          </Alert>
        )}
        <Table size="small" aria-label="Dates avant et après">
          <TableHead>
            <TableRow>
              <TableCell />
              <TableCell>Avant</TableCell>
              <TableCell>Après</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {preview.dates.map((row) => (
              <TableRow key={row.projectId} data-testid="preview-date" data-project={row.name} data-moved={row.before !== row.after}>
                <TableCell component="th" scope="row">
                  {row.name}
                </TableCell>
                <TableCell>{purchaseLabel(row.before)}</TableCell>
                <TableCell>{purchaseLabel(row.after)}</TableCell>
              </TableRow>
            ))}
            <TableRow data-testid="preview-n" data-moved={preview.nDate.before !== preview.nDate.after}>
              <TableCell component="th" scope="row">
                Date N
              </TableCell>
              <TableCell>{nLabel(preview.nDate.before)}</TableCell>
              <TableCell>{nLabel(preview.nDate.after)}</TableCell>
            </TableRow>
          </TableBody>
        </Table>
        <Stack component="dl" spacing={0.5} sx={{ m: 0 }}>
          <Figure term="Coût réel" testId="preview-real-cost" value={formatCents(preview.metrics.realCost, preview.project.currency)} />
          <Figure term="Disponible en" testId="preview-available" value={purchaseLabel(preview.metrics.availableAt)} />
          <Figure term="Impact sur N" testId="preview-impact" value={impactLabel(preview.metrics.nImpactMonths)} />
        </Stack>
      </Stack>
    </Box>
  )
}

const Figure = ({ term, value, testId }: { term: string; value: string; testId: string }) => (
  <Stack direction="row" spacing={1} data-testid={testId}>
    <Typography component="dt" variant="body2" color="text.secondary">
      {term}
    </Typography>
    <Typography component="dd" variant="body2" sx={{ m: 0 }}>
      {value}
    </Typography>
  </Stack>
)
