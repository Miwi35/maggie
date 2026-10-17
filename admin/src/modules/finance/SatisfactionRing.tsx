import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { formatCents } from './accountTypes'
import type { BalanceSatisfaction } from './useMonthBalance'

const SIZE = 148
const STROKE = 14
const RADIUS = (SIZE - STROKE) / 2 - 6
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/**
 * The share, by amount, of what was judged that is worth keeping — and the
 * superfluous amount beside it. Nothing judged yet: an empty ring that says so,
 * never a 0 %.
 */
export const SatisfactionRing = ({ satisfaction }: { satisfaction: BalanceSatisfaction }) => {
  const theme = useTheme()
  const { keptPercent, avoidableCents, unratedCount } = satisfaction
  const centre = SIZE / 2

  return (
    <Box data-testid="satisfaction-ring" sx={{ textAlign: 'center' }}>
      <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
        Satisfaction
      </Typography>

      <Box sx={{ position: 'relative', width: SIZE, height: SIZE, my: 0.5, mx: 'auto' }}>
        <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true">
          <circle
            cx={centre}
            cy={centre}
            r={RADIUS}
            fill="none"
            stroke={theme.palette.action.disabledBackground}
            strokeWidth={STROKE}
          />
          {keptPercent !== null && keptPercent > 0 && (
            <circle
              cx={centre}
              cy={centre}
              r={RADIUS}
              fill="none"
              stroke={theme.palette.success.main}
              strokeWidth={STROKE}
              strokeLinecap="round"
              strokeDasharray={`${(keptPercent / 100) * CIRCUMFERENCE} ${CIRCUMFERENCE}`}
              transform={`rotate(-90 ${centre} ${centre})`}
            />
          )}
        </svg>
        <Stack alignItems="center" justifyContent="center" sx={{ position: 'absolute', inset: 0 }}>
          {keptPercent === null ? (
            <Typography variant="caption" color="text.secondary" data-testid="satisfaction-empty">
              Rien de jugé
            </Typography>
          ) : (
            <>
              <Typography variant="h6" sx={{ lineHeight: 1.1 }} data-testid="satisfaction-percent">
                {keptPercent} %
              </Typography>
              <Typography variant="caption" color="text.secondary">
                à conserver
              </Typography>
            </>
          )}
        </Stack>
      </Box>

      <Typography variant="body2" data-testid="satisfaction-avoidable">
        Superflu : {formatCents(avoidableCents)}
      </Typography>
      {unratedCount > 0 && (
        <Typography variant="caption" color="text.secondary" data-testid="satisfaction-unrated">
          {unratedCount} à juger
        </Typography>
      )}
    </Box>
  )
}
