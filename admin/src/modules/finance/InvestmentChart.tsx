import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'

const WIDTH = 640
const HEIGHT = 180
const PAD = { top: 12, right: 12, bottom: 24, left: 12 }

export interface InvestmentChartProps {
  /** The capital still owed after each month of the loan, first month first. */
  remainingCents: number[]
  monthlyRentCents: number
  /** The months the investment is held before it is resold; the curve stops there. Absent: the whole loan. */
  heldMonths?: number | null
  currency?: string
}

/**
 * The capital still owed against the rents received so far (MAG-557), month by
 * month from the purchase: when the second line passes the first, the rents
 * have repaid the loan. Two lines on one scale, labelled by their end values.
 */
export const InvestmentChart = ({ remainingCents, monthlyRentCents, heldMonths = null, currency = 'EUR' }: InvestmentChartProps) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const owedColour = chartColor(2, mode)
  const rentColour = chartColor(3, mode)

  const months = heldMonths === null ? remainingCents.length : Math.min(remainingCents.length, Math.max(1, heldMonths))
  const owed = remainingCents.slice(0, months)
  if (owed.length === 0) return null

  const rents = owed.map((_, index) => (index + 1) * monthlyRentCents)
  const top = Math.max(1, remainingCents[0] ?? 0, ...rents)
  const plotWidth = WIDTH - PAD.left - PAD.right
  const plotHeight = HEIGHT - PAD.top - PAD.bottom
  const x = (index: number) => PAD.left + (index / Math.max(1, owed.length - 1)) * plotWidth
  const y = (cents: number) => PAD.top + (1 - cents / top) * plotHeight
  const line = (values: number[]) => values.map((value, index) => `${x(index)},${y(value)}`).join(' ')
  const lastOwed = owed[owed.length - 1] ?? 0
  const lastRent = rents[rents.length - 1] ?? 0

  return (
    <Box data-testid="investment-chart">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label={`Capital restant dû et loyers cumulés sur ${owed.length} mois : ${formatCents(lastOwed, currency)} restent à rembourser, ${formatCents(lastRent, currency)} de loyers perçus`}
      >
        <line x1={PAD.left} x2={WIDTH - PAD.right} y1={y(0)} y2={y(0)} stroke={theme.palette.text.secondary} strokeDasharray="4 4" strokeWidth="1" />
        <polyline data-testid="investment-chart-owed" points={line(owed)} fill="none" stroke={owedColour} strokeWidth="2.5" strokeLinejoin="round" />
        <polyline data-testid="investment-chart-rent" points={line(rents)} fill="none" stroke={rentColour} strokeWidth="2.5" strokeLinejoin="round" />
        <text x={PAD.left} y={HEIGHT - 6} fontSize="11" fill={theme.palette.text.secondary}>
          mois 1
        </text>
        <text x={WIDTH - PAD.right} y={HEIGHT - 6} fontSize="11" textAnchor="end" fill={theme.palette.text.secondary}>
          mois {owed.length}
        </text>
      </svg>
      <Stack direction="row" spacing={2} sx={{ mt: 0.5 }}>
        {[
          { label: 'capital restant dû', colour: owedColour },
          { label: 'loyers cumulés', colour: rentColour },
        ].map((entry) => (
          <Stack key={entry.label} direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
            <Box sx={{ width: 12, height: 12, borderRadius: 0.5, bgcolor: entry.colour }} />
            <Typography variant="caption" color="text.secondary">
              {entry.label}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  )
}
