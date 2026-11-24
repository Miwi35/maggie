import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { HEALTH_NUTRITION_PATH } from '../../components/layout/navigation'
import { parisDay } from './dailyEnergySeries'
import { useDailyBalance } from './nutritionApi'
import { BalanceBar, BalanceFigure } from './NutritionBalanceCard'
import { formatInteger } from './healthProfileApi'

/**
 * Santé › Suivi opens on the same balance as Nutrition (MAG-748): today's
 * figure and its bar, in the same tones; a click opens Santé › Nutrition.
 * Read once — the page's one stream is the weighings' (MAG-473).
 */
export const SuiviBalanceBanner = () => {
  const navigate = useNavigate()
  const [today] = useState(() => parisDay(new Date()))
  const balance = useDailyBalance(today)

  if (balance.status !== 'ready') return null
  const day = balance.data

  return (
    <Box
      component="button"
      type="button"
      data-testid="suivi-balance-banner"
      aria-label="Nutrition, bilan du jour : ouvrir Santé › Nutrition"
      onClick={() => navigate(HEALTH_NUTRITION_PATH)}
      sx={(theme) => ({
        appearance: 'none',
        border: 0,
        m: 0,
        font: 'inherit',
        color: 'inherit',
        textAlign: 'left',
        cursor: 'pointer',
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        px: 1.75,
        py: 0.75,
        borderRadius: 1.5,
        bgcolor: theme.palette.background.paper,
        width: 460,
        maxWidth: '100%',
        minWidth: 0,
        fontVariantNumeric: 'tabular-nums',
      })}
    >
      <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary', whiteSpace: 'nowrap' }}>
        Nutrition · aujourd’hui
      </Typography>
      {day.balanceKcal === null ? (
        <Typography component="span" data-testid="balance-eaten" sx={{ fontSize: 14 }}>
          Mangé {formatInteger(day.eatenKcal)} kcal · Prévu {formatInteger(day.plannedKcal)} kcal
        </Typography>
      ) : (
        <>
          <BalanceFigure balanceKcal={day.balanceKcal} size={16} />
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <BalanceBar balanceKcal={day.balanceKcal} height={8} />
          </Box>
        </>
      )}
      <Typography component="span" aria-hidden="true" sx={(theme) => ({ fontSize: 13, color: theme.palette.veilleuse.caption, ml: 'auto' })}>
        ›
      </Typography>
    </Box>
  )
}
