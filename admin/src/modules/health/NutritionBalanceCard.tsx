import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router-dom'
import { HEALTH_CONFIGURATION_PATH } from '../../components/layout/navigation'
import { formatInteger } from './healthProfileApi'
import { BALANCE_ZONE_KCAL, balanceZone, barScale, cardSx, formatSignedKcal, ZONE_WORD, zoneColor } from './nutritionBalance'
import type { BalanceDay } from './nutritionBalance'

const caption = { fontSize: 12, color: (theme: Theme) => theme.palette.veilleuse.caption } as const

interface BalanceBarProps {
  balanceKcal: number
  /** The half-width of the bar, in kcal. */
  scale?: number
  height?: number
}

/**
 * A horizontal bar centred on zero: the shaded « zone d'équilibre » of ± 150 kcal,
 * and the day's balance drawn from zero, in the tone of its zone.
 */
export const BalanceBar = ({ balanceKcal, scale = barScale([balanceKcal]), height = 12 }: BalanceBarProps) => {
  const zone = balanceZone(balanceKcal)
  const half = (value: number) => `${Math.min(50, (Math.abs(value) / scale) * 50).toFixed(2)}%`

  return (
    <Box
      data-testid="balance-bar"
      aria-hidden
      sx={(theme) => ({ position: 'relative', height, borderRadius: height / 2, bgcolor: theme.palette.veilleuse.track, overflow: 'hidden' })}
    >
      <Box
        data-testid="balance-zone"
        sx={(theme) => ({
          position: 'absolute',
          top: 0,
          bottom: 0,
          left: `calc(50% - ${half(BALANCE_ZONE_KCAL)})`,
          width: `calc(2 * ${half(BALANCE_ZONE_KCAL)})`,
          bgcolor: alpha(theme.palette.text.primary, 0.08),
        })}
      />
      <Box
        data-testid="balance-fill"
        data-zone={zone ?? undefined}
        sx={(theme) => ({
          position: 'absolute',
          top: 2,
          bottom: 2,
          ...(balanceKcal >= 0 ? { left: '50%' } : { right: '50%' }),
          width: half(balanceKcal),
          borderRadius: 1,
          bgcolor: zoneColor(theme, zone),
        })}
      />
      <Box sx={(theme) => ({ position: 'absolute', top: 0, bottom: 0, left: '50%', width: '1px', bgcolor: alpha(theme.palette.text.primary, 0.3) })} />
    </Box>
  )
}

/** « +320 kcal » in the tone of its zone, « Équilibre » under it within ± 150 kcal. */
export const BalanceFigure = ({ balanceKcal, size = 36 }: { balanceKcal: number; size?: number }) => {
  const zone = balanceZone(balanceKcal)

  return (
    <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, flexWrap: 'wrap' }}>
      <Typography
        component="span"
        data-testid="balance-value"
        data-zone={zone ?? undefined}
        sx={(theme) => ({ fontSize: size, fontWeight: 300, lineHeight: 1.1, color: zone === 'balanced' ? theme.palette.text.primary : zoneColor(theme, zone) })}
      >
        {formatSignedKcal(balanceKcal)}
      </Typography>
      {zone === 'balanced' && (
        <Typography component="span" data-testid="balance-word" sx={{ fontSize: 14, color: 'text.secondary' }}>
          {ZONE_WORD}
        </Typography>
      )}
    </Box>
  )
}

/** « Mangé 515 kcal · Prévu 0 kcal · Dépensé 1 980 kcal »; no « Dépensé » without an expenditure. */
export const BalanceParts = ({ day }: { day: BalanceDay }) => (
  <Typography component="p" data-testid="balance-parts" sx={{ fontSize: 13, color: 'text.secondary', m: 0 }}>
    <span data-testid="balance-eaten">Mangé {formatInteger(day.eatenKcal)} kcal</span>
    {' · '}
    <span data-testid="balance-planned">Prévu {formatInteger(day.plannedKcal)} kcal</span>
    {day.expendedKcal !== null && (
      <>
        {' · '}
        <span data-testid="balance-expended">Dépensé {formatInteger(day.expendedKcal)} kcal</span>
      </>
    )}
  </Typography>
)

/** The bilan du jour, at the head of Santé › Nutrition. */
export const NutritionBalanceCard = ({ day }: { day: BalanceDay }) => (
  <Box component="section" aria-label="Bilan du jour" sx={cardSx}>
    <Box sx={{ fontSize: 15, fontWeight: 500 }}>Bilan du jour</Box>
    {day.balanceKcal === null ? (
      <Typography data-testid="balance-incomplete" sx={{ fontSize: 14, color: 'text.secondary' }}>
        Complétez votre profil (
        <Link component={RouterLink} to={HEALTH_CONFIGURATION_PATH}>
          Configuration
        </Link>
        ) pour voir le bilan
      </Typography>
    ) : (
      <>
        <BalanceFigure balanceKcal={day.balanceKcal} />
        <BalanceBar balanceKcal={day.balanceKcal} />
        <Box sx={{ display: 'flex', justifyContent: 'center', ...caption }}>
          <span>zone d’équilibre ± {BALANCE_ZONE_KCAL} kcal</span>
        </Box>
      </>
    )}
    <BalanceParts day={day} />
  </Box>
)
