import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { monthLabel } from './scenariosApi'
import type { ComparedScenario, CurvePoint } from './scenariosApi'

const WIDTH = 600
const HEIGHT = 200
const PAD = { left: 34, right: 8, top: 8, bottom: 22 }
const GRID = [0, 0.5, 1]

/** The charter has three chart colours: the fourth to sixth scenarios reuse them, dashed. */
const SLOTS = 3

/** The colour of the nth compared scenario in a chart: the legend and the line share it. */
const scenarioColor = (index: number, mode: 'light' | 'dark'): string => chartColor((index % SLOTS) + 1, mode)

const scenarioDash = (index: number): string | undefined => (index >= SLOTS ? '6 3' : undefined)

/**
 * The coverage curves of the compared scenarios, laid over one another on the
 * same months (0 to 100 % of the train de vie): each scenario a line, named in
 * the legend. No scenario is singled out.
 */
export const ScenarioCurves = ({ scenarios }: { scenarios: ComparedScenario[] }) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const longest = scenarios.reduce<CurvePoint[]>((best, scenario) => (scenario.curve.length > best.length ? scenario.curve : best), [])
  const months = Math.max(2, longest.length)
  const x = (index: number) => PAD.left + (index / (months - 1)) * (WIDTH - PAD.left - PAD.right)
  const y = (coverage: number) => HEIGHT - PAD.bottom - Math.min(1, Math.max(0, coverage)) * (HEIGHT - PAD.top - PAD.bottom)

  const first = longest[0]?.month
  const last = longest[longest.length - 1]?.month

  return (
    <Box data-testid="scenario-curves">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label={`Couverture du train de vie, ${scenarios.map((scenario) => scenario.name).join(', ')}`}
      >
        {GRID.map((level) => (
          <g key={level}>
            <line x1={PAD.left} x2={WIDTH - PAD.right} y1={y(level)} y2={y(level)} stroke={theme.palette.divider} vectorEffect="non-scaling-stroke" />
            <text x={PAD.left - 4} y={y(level) + 4} textAnchor="end" fontSize={11} fill={theme.palette.text.secondary}>
              {level * 100} %
            </text>
          </g>
        ))}
        {scenarios.map((scenario, index) => (
          <polyline
            key={scenario.id}
            data-testid="scenario-curve"
            data-scenario={scenario.name}
            fill="none"
            stroke={scenarioColor(index, mode)}
            strokeWidth={2}
            strokeLinejoin="round"
            strokeDasharray={scenarioDash(index)}
            vectorEffect="non-scaling-stroke"
            points={scenario.curve.map((point, position) => `${x(position)},${y(point.coverage)}`).join(' ')}
          />
        ))}
        {first && (
          <text x={PAD.left} y={HEIGHT - 4} fontSize={11} fill={theme.palette.text.secondary}>
            {monthLabel(first)}
          </text>
        )}
        {last && (
          <text x={WIDTH - PAD.right} y={HEIGHT - 4} textAnchor="end" fontSize={11} fill={theme.palette.text.secondary}>
            {monthLabel(last)}
          </text>
        )}
      </svg>
      <Stack data-testid="scenario-curves-legend" direction="row" sx={{ flexWrap: 'wrap', gap: 2, mt: 1 }}>
        {scenarios.map((scenario, index) => (
          <Stack key={scenario.id} direction="row" sx={{ alignItems: 'center', gap: 0.75 }}>
            <Box
              aria-hidden
              sx={{ width: 18, height: 0, borderTop: `3px ${scenarioDash(index) ? 'dashed' : 'solid'} ${scenarioColor(index, mode)}` }}
            />
            <Typography variant="body2">{scenario.name}</Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}
