import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { formatMonth } from './loans'
import type { LoanCurve } from './useLoanSimulation'

const WIDTH = 640
const HEIGHT = 200
const PAD = { top: 12, right: 12, bottom: 24, left: 12 }

/** The capital still owed, month by month, down to the month the loan ends. */
export const LoanBalanceChart = ({ curve, currency = 'EUR' }: { curve: LoanCurve; currency?: string }) => {
  const theme = useTheme()
  const line = chartColor(1, theme.palette.mode === 'dark' ? 'dark' : 'light')
  const points = [{ month: 'Aujourd\'hui', remainingCents: curve.principalRemainingCents }, ...curve.months]
  const top = Math.max(1, curve.principalRemainingCents)
  const plotWidth = WIDTH - PAD.left - PAD.right
  const plotHeight = HEIGHT - PAD.top - PAD.bottom
  const x = (index: number) => PAD.left + (index / Math.max(1, points.length - 1)) * plotWidth
  const y = (cents: number) => PAD.top + (1 - cents / top) * plotHeight
  const last = curve.months[curve.months.length - 1]

  return (
    <Box data-testid="loan-balance" sx={{ mt: 3, maxWidth: 680 }}>
      <Typography variant="h6" sx={{ mb: 1 }}>
        Capital restant dû
      </Typography>
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label="Courbe du capital restant dû, mois par mois, jusqu'à la fin du prêt"
      >
        <line x1={PAD.left} x2={WIDTH - PAD.right} y1={y(0)} y2={y(0)} stroke={theme.palette.text.secondary} strokeDasharray="4 4" strokeWidth="1" />
        <polyline
          points={points.map((point, index) => `${x(index)},${y(point.remainingCents)}`).join(' ')}
          fill="none"
          stroke={line}
          strokeWidth="2.5"
          strokeLinejoin="round"
        />
        <text x={PAD.left} y={HEIGHT - 6} fontSize="11" fill={theme.palette.text.secondary}>
          {formatCents(curve.principalRemainingCents, currency)}
        </text>
        <text x={WIDTH - PAD.right} y={HEIGHT - 6} fontSize="11" textAnchor="end" fill={theme.palette.text.secondary}>
          {last ? formatMonth(last.month) : ''}
        </text>
      </svg>
      <Typography variant="body2" color="text.secondary" data-testid="loan-balance-end">
        {curve.freedOn ? `Fin en ${formatMonth(curve.freedOn)}` : 'Au-delà de la durée calculée'}
      </Typography>
    </Box>
  )
}
