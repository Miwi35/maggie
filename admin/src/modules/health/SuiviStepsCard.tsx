import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { energyDayLabel, hasSteps } from './dailyEnergySeries'
import type { DailyEnergyState } from './dailyEnergyApi'
import { formatInteger } from './healthProfileApi'
import { NONE } from './suiviSeries'
import { SuiviAxis } from './SuiviAxis'
import type { SuiviAxisProps } from './SuiviAxis'

interface SuiviStepsCardProps {
  state: DailyEnergyState
  today: string
  /** The chosen day; null: the last one, today. */
  selected: number | null
  onSelect: (index: number | null) => void
  axis: SuiviAxisProps
}

const caption = { fontSize: 12, color: (theme: Theme) => theme.palette.veilleuse.caption } as const
const cardSx = { borderRadius: 2, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0, fontVariantNumeric: 'tabular-nums' } as const

/** « Pas »: the steps of each day as bars; the title carries the chosen day's figure, today's by default. */
export const SuiviStepsCard = ({ state, today, selected, onSelect, axis }: SuiviStepsCardProps) => {
  const rows = state.status === 'ready' ? state.rows : []
  const current = selected ?? rows.length - 1
  const row = rows[current]
  const value = row ? row.steps : null
  const scale = Math.max(0, ...rows.map((day) => day.steps ?? 0))

  return (
    <Box component="section" aria-label="Pas" sx={cardSx}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 15, fontWeight: 500 }}>
        <span>Pas</span>
        <Typography component="span" sx={{ fontSize: 13, fontWeight: 400, color: 'text.secondary' }}>
          <span data-testid="steps-label">{row ? energyDayLabel(row, today) : ''}</span>
          {' · '}
          <span data-testid="steps-value">{value === null ? NONE : formatInteger(value)}</span>
        </Typography>
      </Box>
      {state.status === 'loading' && <Typography sx={caption}>Chargement…</Typography>}
      {state.status === 'error' && <Typography sx={caption}>Erreur : les pas n’ont pas pu être chargés.</Typography>}
      {state.status === 'ready' && !hasSteps(rows) && <Typography sx={caption}>Aucune donnée de pas</Typography>}
      {state.status === 'ready' && hasSteps(rows) && (
        <>
          <Box sx={{ display: 'flex', alignItems: 'flex-end', gap: '2px', height: 140 }}>
            {rows.map((day, index) => (
              <Box
                key={day.date}
                component="button"
                type="button"
                aria-label={`${energyDayLabel(day, today)} : ${day.steps === null ? 'pas de donnée' : `${formatInteger(day.steps)} pas`}`}
                aria-pressed={index === current}
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
                  justifyContent: 'center',
                  borderRadius: 1,
                  bgcolor: index === current ? alpha(theme.palette.text.primary, 0.07) : 'transparent',
                })}
              >
                <Box
                  sx={(theme) => ({
                    width: '56%',
                    height: `${scale > 0 ? (((day.steps ?? 0) / scale) * 100).toFixed(1) : 0}%`,
                    borderRadius: '3px 3px 1px 1px',
                    bgcolor: index === current ? theme.palette.module.sport : alpha(theme.palette.module.sport, 0.42),
                  })}
                />
              </Box>
            ))}
          </Box>
          <SuiviAxis from={axis.from} to={axis.to} />
        </>
      )}
    </Box>
  )
}
