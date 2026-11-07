import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { balanceZone, barScale, BALANCE_ZONE_KCAL, cardSx, dayLabel, formatSignedKcal, zoneColor } from './nutritionBalance'
import type { BalanceDay } from './nutritionBalance'
import type { Loaded } from './nutritionApi'

interface NutritionWeekCardProps {
  state: Loaded<BalanceDay[]>
  /** The day the page shows, the last bar. */
  day: string
  today: string
}

const BAR_HEIGHT = 120

/** « 7 jours »: one bar a day, up for a surplus and down for a deficit, around zero. */
export const NutritionWeekCard = ({ state, day, today }: NutritionWeekCardProps) => {
  const days = state.status === 'ready' ? state.data : []
  const scale = barScale(days.map((row) => row.balanceKcal))
  const share = (value: number) => `${Math.min(50, (Math.abs(value) / scale) * 50).toFixed(2)}%`

  return (
    <Box component="section" aria-label="7 jours" sx={cardSx}>
      <Box sx={{ fontSize: 15, fontWeight: 500 }}>7 jours</Box>
      {state.status === 'loading' && <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Chargement…</Typography>}
      {state.status === 'error' && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Erreur : les 7 jours n’ont pas pu être chargés.</Typography>
      )}
      {state.status === 'ready' && (
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'stretch' }}>
          {days.map((row) => {
            const label = dayLabel(row.date, today)
            const zone = balanceZone(row.balanceKcal)

            return (
              <Box
                key={row.date}
                data-testid="week-day"
                role="img"
                aria-label={`${label} : ${row.balanceKcal === null ? 'pas de bilan' : formatSignedKcal(row.balanceKcal)}`}
                sx={{ flex: '1 1 0', minWidth: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0.5 }}
              >
                <Box
                  sx={(theme) => ({
                    position: 'relative',
                    width: '100%',
                    height: BAR_HEIGHT,
                    borderRadius: 1,
                    bgcolor: row.date === day ? alpha(theme.palette.text.primary, 0.05) : 'transparent',
                  })}
                >
                  <Box
                    sx={(theme) => ({
                      position: 'absolute',
                      left: 0,
                      right: 0,
                      top: `calc(50% - ${share(BALANCE_ZONE_KCAL)})`,
                      height: `calc(2 * ${share(BALANCE_ZONE_KCAL)})`,
                      bgcolor: alpha(theme.palette.text.primary, 0.06),
                    })}
                  />
                  <Box sx={(theme) => ({ position: 'absolute', left: 0, right: 0, top: '50%', height: '1px', bgcolor: alpha(theme.palette.text.primary, 0.25) })} />
                  {row.balanceKcal !== null && (
                    <Box
                      data-testid="week-bar"
                      data-zone={zone ?? undefined}
                      sx={(theme) => ({
                        position: 'absolute',
                        left: '25%',
                        right: '25%',
                        ...(row.balanceKcal !== null && row.balanceKcal >= 0 ? { bottom: '50%' } : { top: '50%' }),
                        height: share(row.balanceKcal ?? 0),
                        borderRadius: '3px',
                        bgcolor: zoneColor(theme, zone),
                      })}
                    />
                  )}
                </Box>
                <Typography component="span" sx={{ fontSize: 11, color: 'text.secondary', textAlign: 'center', lineHeight: 1.2 }}>
                  {label}
                </Typography>
              </Box>
            )
          })}
        </Box>
      )}
    </Box>
  )
}
