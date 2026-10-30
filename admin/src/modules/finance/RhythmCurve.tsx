import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { LABEL_PX, placeLabels, textWidthPx, truncate, useUnitsPerPixel } from './chartText'
import type { Rhythm } from './useFinanceScores'

const WIDTH = 640
const HEIGHT = 200
const PAD = { left: 8, right: 8, bottom: 8 }
const LABEL_ROW_PX = 14
const LABEL_GAP_PX = 8
const LABEL_MAX_ROWS = 3
const LABEL_MAX_CHARS = 14

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
  const { ref, unitsPerPixel } = useUnitsPerPixel(WIDTH)

  const peak = Math.max(
    1,
    ...curve.map((point) => Math.max(point.spentCents ?? 0, point.expectedCents)),
  )
  const x = (index: number) =>
    PAD.left + (curve.length > 1 ? (index / (curve.length - 1)) * (WIDTH - PAD.left - PAD.right) : 0)

  const drawn = debits
    .map((debit) => ({ debit, index: curve.findIndex((point) => point.date === debit.date) }))
    .filter(({ index }) => index >= 0)
    .map(({ debit, index }) => ({ debit, index, text: `${truncate(debit.label, LABEL_MAX_CHARS)} ${dayOf(debit.date)}` }))
  const placed = placeLabels(
    drawn.map(({ index, text }) => ({ centerPx: x(index) / unitsPerPixel, widthPx: textWidthPx(text) })),
    WIDTH / unitsPerPixel,
    LABEL_GAP_PX,
    LABEL_MAX_ROWS,
  )
  const rows = placed.reduce((count, { row }) => Math.max(count, row + 1), 1)
  const top = (rows * LABEL_ROW_PX + 8) * unitsPerPixel
  const y = (cents: number) => HEIGHT - PAD.bottom - (cents / peak) * (HEIGHT - top - PAD.bottom)

  const path = (values: (number | null)[]) =>
    values
      .map((value, index) => (value === null ? null : `${x(index)},${y(value)}`))
      .filter((point): point is string => point !== null)
      .map((point, index) => `${index === 0 ? 'M' : 'L'}${point}`)
      .join(' ')

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
        ref={ref}
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
        {drawn.map(({ debit, index, text }, position) => (
          <g
            key={`${debit.date}-${debit.recurringOperationId}-${debit.label}`}
            data-testid="debit-marker"
            data-label={debit.label}
            data-date={debit.date}
          >
            <line
              x1={x(index)}
              x2={x(index)}
              y1={top - 6 * unitsPerPixel}
              y2={HEIGHT - PAD.bottom}
              stroke={theme.palette.text.disabled}
              strokeDasharray="2 3"
            />
            <circle cx={x(index)} cy={y(curve[index].expectedCents)} r={4} fill={expectedColour} />
            <text
              x={placed[position].xPx * unitsPerPixel}
              y={(placed[position].row * LABEL_ROW_PX + LABEL_ROW_PX - 3) * unitsPerPixel}
              textAnchor="middle"
              fontSize={LABEL_PX * unitsPerPixel}
              fill={theme.palette.text.secondary}
            >
              {text}
            </text>
          </g>
        ))}
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
