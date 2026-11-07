import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { energyDayLabel, energyParts, maxStack, orNone } from './dailyEnergySeries'
import type { DailyEnergyState } from './dailyEnergyApi'
import { formatInteger } from './healthProfileApi'
import { NONE } from './suiviSeries'

interface SuiviEnergyCardProps {
  state: DailyEnergyState
  today: string
  /** The chosen day; null: the last one, today. */
  selected: number | null
  onSelect: (index: number | null) => void
}

const caption = { fontSize: 12, color: (theme: Theme) => theme.palette.veilleuse.caption } as const
const cardSx = { borderRadius: 4, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0, fontVariantNumeric: 'tabular-nums' } as const

const PARTS = [
  { key: 'base', label: 'base', color: (theme: Theme) => alpha(theme.palette.text.primary, 0.28) },
  { key: 'steps', label: 'pas', color: (theme: Theme) => alpha(theme.palette.module.sport, 0.5) },
  { key: 'workouts', label: 'séances', color: (theme: Theme) => theme.palette.module.sport },
] as const

const kcal = (value: number | null) => `${orNone(value, formatInteger)} kcal`

/** « Énergie »: per day, base · pas · séances stacked; the total is the API's, « — » when the profile is incomplete. */
export const SuiviEnergyCard = ({ state, today, selected, onSelect }: SuiviEnergyCardProps) => {
  const rows = state.status === 'ready' ? state.rows : []
  const row = selected === null ? rows.at(-1) : rows[selected]
  const scale = maxStack(rows)
  const parts = row ? energyParts(row) : null

  return (
    <Box component="section" aria-label="Énergie" sx={cardSx}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 15, fontWeight: 500 }}>
        <span>Énergie</span>
        <Typography component="span" data-testid="energy-label" sx={{ ...caption, fontWeight: 400 }}>
          {row ? energyDayLabel(row, today) : ''}
        </Typography>
      </Box>
      {state.status === 'loading' && <Typography sx={caption}>Chargement…</Typography>}
      {state.status === 'error' && <Typography sx={caption}>Erreur : l’énergie n’a pas pu être chargée.</Typography>}
      {state.status === 'ready' && row && parts && (
        <>
          <Typography component="span" data-testid="energy-total" sx={{ fontSize: 26, fontWeight: 300 }}>
            {row.expendedKcal === null ? NONE : formatInteger(row.expendedKcal)}{' '}
            <Box component="span" sx={{ fontSize: 15 }}>
              kcal
            </Box>
          </Typography>
          <Typography component="span" data-testid="energy-detail" sx={{ fontSize: 13, color: 'text.secondary' }}>
            base {kcal(row.bmrKcal)} · pas {kcal(row.stepsKcal)} · séances {kcal(row.workoutKcal)}
          </Typography>
          <Box sx={{ display: 'flex', alignItems: 'flex-end', gap: '2px', height: 140 }}>
            {rows.map((day, index) => {
              const dayParts = energyParts(day)

              return (
                <Box
                  key={day.date}
                  component="button"
                  type="button"
                  aria-label={`${energyDayLabel(day, today)} : ${kcal(day.expendedKcal)}`}
                  aria-pressed={row === day}
                  data-testid="energy-bar"
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
                    flexDirection: 'column',
                    justifyContent: 'flex-end',
                    borderRadius: 1,
                    bgcolor: row === day ? alpha(theme.palette.text.primary, 0.07) : 'transparent',
                  })}
                >
                  {[...PARTS].reverse().map((part) => (
                    <Box
                      key={part.key}
                      sx={(theme) => ({
                        width: '100%',
                        height: `${scale > 0 ? ((dayParts[part.key] / scale) * 100).toFixed(1) : 0}%`,
                        bgcolor: part.color(theme),
                      })}
                    />
                  ))}
                </Box>
              )
            })}
          </Box>
          <Box sx={{ display: 'flex', gap: 1.5, ...caption }}>
            {PARTS.map((part) => (
              <Box key={part.key} component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
                <Box component="span" sx={(theme) => ({ width: 8, height: 8, borderRadius: '2px', bgcolor: part.color(theme) })} />
                {part.label}
              </Box>
            ))}
          </Box>
        </>
      )}
    </Box>
  )
}
