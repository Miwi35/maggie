import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import type { PeriodMonth } from './usePeriodBalance'

const WIDTH = 640
const HEIGHT = 200
const PAD = { left: 12, right: 12, top: 16, bottom: 28 }
const SHORT_MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.']

/** The months of a period against each other: budgeted (dashed), spent and put aside, one point per month. */
export const PeriodCurve = ({ months }: { months: PeriodMonth[] }) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const series = [
    { key: 'budgetCents', label: 'Budgété', colour: chartColor(1, mode), dashed: true },
    { key: 'spentCents', label: 'Dépensé', colour: chartColor(2, mode), dashed: false },
    { key: 'savedCents', label: 'Épargné', colour: theme.palette.success.main, dashed: false },
  ] as const

  const peak = Math.max(1, ...months.flatMap((month) => [month.budgetCents, month.spentCents, month.savedCents]))
  const x = (index: number) =>
    PAD.left + (months.length > 1 ? (index / (months.length - 1)) * (WIDTH - PAD.left - PAD.right) : (WIDTH - PAD.left - PAD.right) / 2)
  const y = (cents: number) => HEIGHT - PAD.bottom - (cents / peak) * (HEIGHT - PAD.top - PAD.bottom)

  return (
    <Box data-testid="period-curve">
      <Stack direction="row" spacing={2} sx={{ mb: 1 }}>
        {series.map((line) => (
          <Stack key={line.key} direction="row" spacing={0.5} alignItems="center">
            <Box sx={{ width: 18, height: 0, borderTop: `3px ${line.dashed ? 'dashed' : 'solid'} ${line.colour}` }} />
            <Typography variant="caption">{line.label}</Typography>
          </Stack>
        ))}
      </Stack>

      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label={`Mois par mois : ${months.map((month) => `${month.month}, ${formatCents(month.spentCents)} dépensés sur ${formatCents(month.budgetCents)} budgétés`).join(' ; ')}`}
      >
        <line x1={PAD.left} x2={WIDTH - PAD.right} y1={HEIGHT - PAD.bottom} y2={HEIGHT - PAD.bottom} stroke={theme.palette.divider} />
        {series.map((line) => (
          <path
            key={line.key}
            data-testid={`period-line-${line.key}`}
            d={months.map((month, index) => `${index === 0 ? 'M' : 'L'}${x(index)},${y(month[line.key])}`).join(' ')}
            fill="none"
            stroke={line.colour}
            strokeWidth={line.dashed ? 2 : 3}
            strokeDasharray={line.dashed ? '6 4' : undefined}
            strokeLinejoin="round"
          />
        ))}
        {months.map((month, index) => (
          <text key={month.month} x={x(index)} y={HEIGHT - 8} textAnchor="middle" fontSize={11} fill={theme.palette.text.secondary}>
            {SHORT_MONTHS[Number(month.month.slice(5, 7)) - 1]}
          </text>
        ))}
      </svg>
    </Box>
  )
}
