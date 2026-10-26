import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { TOKENS } from '../../design/tokens'
import { formatCentsRounded } from './accountTypes'
import { yearsLabel } from './placementsApi'
import type { SimulationPoint } from './placementsApi'

const WIDTH = 600
const HEIGHT = 160
const PAD = { left: 4, right: 4, top: 8, bottom: 20 }

/**
 * A placement month by month (MAG-555): what was paid in, and on top of it the
 * compound interest, two stacked areas whose top is the capital. When the
 * projects of a scenario take capital out of it (MAG-556), the capital alone,
 * without the withdrawals (dashed) and with them (solid).
 */
export const PlacementCurve = ({
  name,
  curve,
  years,
  withdrawal,
}: {
  name: string
  curve: SimulationPoint[]
  years: number
  /** The capital with and without what the projects take out (MAG-556): drawn as two lines instead of the areas. */
  withdrawal?: { curveWithout: SimulationPoint[]; curveWith: SimulationPoint[] }
}) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const paidColour = TOKENS.feedback[mode].info
  const interestColour = TOKENS.rhythm.onTrack[mode]

  const every = withdrawal ? [...withdrawal.curveWithout, ...withdrawal.curveWith] : curve
  const lastMonth = Math.max(1, ...every.map((point) => point.month))
  const peak = Math.max(1, ...every.map((point) => Math.max(point.capital, point.paid)))
  const x = (month: number) => PAD.left + (month / lastMonth) * (WIDTH - PAD.left - PAD.right)
  const y = (cents: number) => HEIGHT - PAD.bottom - (cents / peak) * (HEIGHT - PAD.top - PAD.bottom)
  const base = y(0)

  const line = (points: SimulationPoint[], value: (point: SimulationPoint) => number) =>
    points.map((point) => `${x(point.month)},${y(value(point))}`).join(' L')

  const first = curve[0]
  const last = curve[curve.length - 1]
  const paidArea = curve.length > 0 ? `M${x(first.month)},${base} L${line(curve, (point) => point.paid)} L${x(last.month)},${base} Z` : ''
  const interestArea =
    curve.length > 0
      ? `M${line(curve, (point) => point.capital)} L${line([...curve].reverse(), (point) => point.paid)} Z`
      : ''

  const lastOf = (points: SimulationPoint[]) => points[points.length - 1]
  const capitalLine = (points: SimulationPoint[]) => (points.length > 0 ? `M${line(points, (point) => point.capital)}` : '')
  const withoutColour = TOKENS.feedback[mode].info
  const withColour = interestColour

  const ticks = years >= 4 ? [0, Math.round(years / 2), years] : [0, years]

  return (
    <Box data-testid="placement-curve">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label={
          withdrawal
            ? `Courbe de ${name} sur ${yearsLabel(years)} : ${formatCentsRounded(lastOf(withdrawal.curveWithout)?.capital ?? 0)} de capital à la fin sans retrait, ${formatCentsRounded(lastOf(withdrawal.curveWith)?.capital ?? 0)} avec retrait`
            : `Courbe de ${name} sur ${yearsLabel(years)} : ${formatCentsRounded(last?.paid ?? 0)} versés, ${formatCentsRounded(last?.capital ?? 0)} de capital à la fin`
        }
      >
        {withdrawal ? (
          <>
            <path
              data-testid="placement-curve-without"
              d={capitalLine(withdrawal.curveWithout)}
              fill="none"
              stroke={withoutColour}
              strokeWidth={2}
              strokeDasharray="6 4"
              vectorEffect="non-scaling-stroke"
            />
            <path
              data-testid="placement-curve-with"
              d={capitalLine(withdrawal.curveWith)}
              fill="none"
              stroke={withColour}
              strokeWidth={2}
              vectorEffect="non-scaling-stroke"
            />
          </>
        ) : (
          <>
            <path data-testid="placement-curve-interest" d={interestArea} fill={interestColour} fillOpacity={0.55} stroke={interestColour} strokeWidth={2} vectorEffect="non-scaling-stroke" />
            <path data-testid="placement-curve-paid" d={paidArea} fill={paidColour} fillOpacity={0.55} stroke={paidColour} strokeWidth={2} vectorEffect="non-scaling-stroke" />
          </>
        )}
        <line x1={PAD.left} x2={WIDTH - PAD.right} y1={base} y2={base} stroke={theme.palette.divider} vectorEffect="non-scaling-stroke" />
        {ticks.map((year, index) => (
          <text
            key={year}
            x={x(year * 12)}
            y={HEIGHT - 4}
            textAnchor={index === 0 ? 'start' : index === ticks.length - 1 ? 'end' : 'middle'}
            fontSize={11}
            fill={theme.palette.text.secondary}
          >
            {year === 0 ? 'aujourd’hui' : yearsLabel(year)}
          </text>
        ))}
      </svg>
      <Stack direction="row" spacing={2} sx={{ mt: 0.5 }}>
        {(withdrawal
          ? [
              { label: 'capital sans retrait', colour: withoutColour, dashed: true },
              { label: 'capital avec retrait', colour: withColour, dashed: false },
            ]
          : [
              { label: 'versements cumulés', colour: paidColour, dashed: false },
              { label: 'intérêts composés', colour: interestColour, dashed: false },
            ]
        ).map((entry) => (
          <Stack key={entry.label} direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
            {withdrawal ? (
              <Box sx={{ width: 18, height: 0, borderTop: 2, borderTopStyle: entry.dashed ? 'dashed' : 'solid', borderColor: entry.colour }} />
            ) : (
              <Box sx={{ width: 12, height: 12, borderRadius: 0.5, bgcolor: entry.colour, opacity: 0.8 }} />
            )}
            <Typography variant="caption" color="text.secondary">
              {entry.label}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}
