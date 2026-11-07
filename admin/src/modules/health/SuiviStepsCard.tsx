import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { averageSteps, energyDayLabel, hasSteps } from './dailyEnergySeries'
import type { DailyEnergyState } from './dailyEnergyApi'
import { formatInteger } from './healthProfileApi'
import { NONE } from './suiviSeries'

interface SuiviStepsCardProps {
  state: DailyEnergyState
  today: string
  /** The chosen day; null: the whole period, shown as its average. */
  selected: number | null
  onSelect: (index: number | null) => void
}

const caption = { fontSize: 12, color: (theme: Theme) => theme.palette.veilleuse.caption } as const
const cardSx = { borderRadius: 4, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0, fontVariantNumeric: 'tabular-nums' } as const

/** « Pas »: the steps of each day as bars; the figure is the chosen day's, or the period's average. */
export const SuiviStepsCard = ({ state, today, selected, onSelect }: SuiviStepsCardProps) => {
  const rows = state.status === 'ready' ? state.rows : []
  const row = selected === null ? undefined : rows[selected]
  const value = row ? row.steps : averageSteps(rows)
  const scale = Math.max(0, ...rows.map((day) => day.steps ?? 0))

  return (
    <Box component="section" aria-label="Pas" sx={cardSx}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 15, fontWeight: 500 }}>
        <span>Pas</span>
        <Typography component="span" data-testid="steps-label" sx={{ ...caption, fontWeight: 400 }}>
          {row ? energyDayLabel(row, today) : 'Moyenne par jour'}
        </Typography>
      </Box>
      {state.status === 'loading' && <Typography sx={caption}>Chargement…</Typography>}
      {state.status === 'error' && <Typography sx={caption}>Erreur : les pas n’ont pas pu être chargés.</Typography>}
      {state.status === 'ready' && !hasSteps(rows) && <Typography sx={caption}>Aucune donnée de pas</Typography>}
      {state.status === 'ready' && hasSteps(rows) && (
        <>
          <Typography component="span" data-testid="steps-value" sx={{ fontSize: 26, fontWeight: 300 }}>
            {value === null ? NONE : formatInteger(value)}
          </Typography>
          <Box sx={{ display: 'flex', alignItems: 'flex-end', gap: '2px', height: 140 }}>
            {rows.map((day, index) => (
              <Box
                key={day.date}
                component="button"
                type="button"
                aria-label={`${energyDayLabel(day, today)} : ${day.steps === null ? 'pas de donnée' : `${formatInteger(day.steps)} pas`}`}
                aria-pressed={index === selected}
                data-testid="steps-bar"
                onClick={() => onSelect(index === selected ? null : index)}
                sx={(theme) => ({
                  appearance: 'none',
                  border: 0,
                  m: 0,
                  p: 0,
                  cursor: 'pointer',
                  flex: '1 1 0',
                  minWidth: 0,
                  height: '100%',
                  display: 'flex',
                  alignItems: 'flex-end',
                  borderRadius: 1,
                  bgcolor: index === selected ? alpha(theme.palette.text.primary, 0.07) : 'transparent',
                })}
              >
                <Box
                  sx={(theme) => ({
                    width: '100%',
                    height: `${scale > 0 ? (((day.steps ?? 0) / scale) * 100).toFixed(1) : 0}%`,
                    borderRadius: '3px 3px 1px 1px',
                    bgcolor: theme.palette.module.sport,
                  })}
                />
              </Box>
            ))}
          </Box>
        </>
      )}
    </Box>
  )
}
