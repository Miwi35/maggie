import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import type { Rhythm } from './useFinanceScores'

const WIDTH = 640
const HEIGHT = 200
const PAD = { left: 8, right: 8, top: 24, bottom: 8 }

const dayOf = (date: string) => String(Number(date.slice(8, 10)))

interface RhythmCurveProps {
  rhythm: Rhythm
}

/**
 * What has been spent, cumulated, against what the pace expected, with each
 * direct debit marked on its day. The expected curve steps at the debits —
 * the rent lands at once — so it is not a straight line.
 */
export const RhythmCurve = ({ rhythm }: RhythmCurveProps) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const spentColour = chartColor(2, mode)
  const expectedColour = chartColor(1, mode)
  const { curve, debits } = rhythm

  const peak = Math.max(
    1,
    ...curve.map((point) => Math.max(point.spentCents ?? 0, point.expectedCents)),
  )
  const x = (index: number) =>
    PAD.left + (curve.length > 1 ? (index / (curve.length - 1)) * (WIDTH - PAD.left - PAD.right) : 0)
  const y = (cents: number) =>
    HEIGHT - PAD.bottom - (cents / peak) * (HEIGHT - PAD.top - PAD.bottom)

  const path = (values: (number | null)[]) =>
    values
      .map((value, index) => (value === null ? null : `${x(index)},${y(value)}`))
      .filter((point): point is string => point !== null)
      .map((point, index) => `${index === 0 ? 'M' : 'L'}${point}`)
      .join(' ')

  const indexOfDay = (date: string) => curve.findIndex((point) => point.date === date)

  return (
    <Box data-testid="rhythm-curve">
      <Stack direction="row" spacing={2} sx={{ mb: 1 }}>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Box sx={{ width: 18, height: 3, bgcolor: spentColour }} />
          <Typography variant="caption">Dépensé</Typography>
        </Stack>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Box sx={{ width: 18, height: 0, borderTop: `3px dashed ${expectedColour}` }} />
          <Typography variant="caption">Attendu</Typography>
        </Stack>
      </Stack>

      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label={`Dépensé cumulé : ${formatCents(rhythm.spentCents)} pour ${formatCents(rhythm.expectedSpentCents)} attendus`}
      >
        <line
          x1={PAD.left}
          x2={WIDTH - PAD.right}
          y1={HEIGHT - PAD.bottom}
          y2={HEIGHT - PAD.bottom}
          stroke={theme.palette.divider}
        />
        <path
          d={path(curve.map((point) => point.expectedCents))}
          fill="none"
          stroke={expectedColour}
          strokeWidth={2}
          strokeDasharray="6 4"
        />
        <path
          data-testid="spent-line"
          d={path(curve.map((point) => point.spentCents))}
          fill="none"
          stroke={spentColour}
          strokeWidth={3}
          strokeLinejoin="round"
        />
        {debits.map((debit) => {
          const index = indexOfDay(debit.date)
          if (index < 0) {
            return null
          }

          return (
            <g
              key={`${debit.date}-${debit.recurringOperationId}-${debit.label}`}
              data-testid="debit-marker"
              data-label={debit.label}
              data-date={debit.date}
            >
              <line
                x1={x(index)}
                x2={x(index)}
                y1={PAD.top - 6}
                y2={HEIGHT - PAD.bottom}
                stroke={theme.palette.text.disabled}
                strokeDasharray="2 3"
              />
              <circle cx={x(index)} cy={y(curve[index].expectedCents)} r={4} fill={expectedColour} />
              <text
                x={Math.min(Math.max(x(index), 30), WIDTH - 30)}
                y={PAD.top - 10}
                textAnchor="middle"
                fontSize={11}
                fill={theme.palette.text.secondary}
              >
                {debit.label} {dayOf(debit.date)}
              </text>
            </g>
          )
        })}
      </svg>

      {debits.length > 0 && (
        <Box component="ul" sx={{ m: 0, mt: 1, pl: 2 }}>
          {debits.map((debit) => (
            <Typography
              component="li"
              variant="caption"
              color="text.secondary"
              key={`${debit.date}-${debit.recurringOperationId}-${debit.label}`}
            >
              {debit.label} — le {dayOf(debit.date)} — {formatCents(debit.amountCents)}
            </Typography>
          ))}
        </Box>
      )}
    </Box>
  )
}
