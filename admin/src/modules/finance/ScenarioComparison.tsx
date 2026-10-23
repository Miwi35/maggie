import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import { formatCents } from './accountTypes'
import { ScenarioCurves } from './ScenarioCurves'
import { monthLabel, nLabel } from './scenariosApi'
import type { Comparison } from './scenariosApi'
import type { ComparisonStatus } from './useScenarios'

interface Props {
  status: ComparisonStatus
  comparison: Comparison | null
  error: string | null
}

/**
 * The compared scenarios: their coverage curves laid over one another, then one
 * row each — N, the gap to the reference (the API's own sentence), wellbeing,
 * when the projects are done, when the debts end, the interest paid. Facts only.
 */
export const ScenarioComparison = ({ status, comparison, error }: Props) => {
  if (status === 'idle') return null
  if (status === 'loading') return <Skeleton data-testid="comparison-loading" height={160} />
  if (status === 'failed' || !comparison) return <Alert severity="error">{error ?? 'La comparaison ne peut pas être chargée.'}</Alert>

  const sentenceOf = (id: string) => comparison.differences.find((difference) => difference.scenarioId === id)?.sentence

  return (
    <Box data-testid="scenario-comparison">
      <ScenarioCurves scenarios={comparison.scenarios} />
      <Box sx={{ overflowX: 'auto', mt: 2 }}>
        <Table size="small" aria-label="Comparaison des scénarios">
          <TableHead>
            <TableRow>
              <TableCell>Scénario</TableCell>
              <TableCell>N</TableCell>
              <TableCell>Écart de N</TableCell>
              <TableCell align="right">Bien-être</TableCell>
              <TableCell>Projets bouclés</TableCell>
              <TableCell>Fin des dettes</TableCell>
              <TableCell align="right">Intérêts payés</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {comparison.scenarios.map((scenario) => (
              <TableRow key={scenario.id} data-testid="comparison-row" data-scenario={scenario.name}>
                <TableCell component="th" scope="row">
                  {scenario.name}
                </TableCell>
                <TableCell>{nLabel(scenario)}</TableCell>
                <TableCell>{scenario.id === comparison.referenceId ? 'Référence' : (sentenceOf(scenario.id) ?? '—')}</TableCell>
                <TableCell align="right">{scenario.metrics.wellbeing}</TableCell>
                <TableCell>{scenario.metrics.projectsDoneBy ? monthLabel(scenario.metrics.projectsDoneBy) : "Au-delà de l'horizon"}</TableCell>
                <TableCell>{monthLabel(scenario.metrics.debtsEndDate)}</TableCell>
                <TableCell align="right">{formatCents(scenario.metrics.interestPaid)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
    </Box>
  )
}
