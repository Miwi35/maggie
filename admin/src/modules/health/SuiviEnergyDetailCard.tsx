import Box from '@mui/material/Box'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { energyDayLabel } from './dailyEnergySeries'
import type { DailyEnergyState } from './dailyEnergyApi'
import { kcal, PARTS } from './SuiviEnergyCard'

interface SuiviEnergyDetailCardProps {
  state: DailyEnergyState
  today: string
  /** The chosen day; null: the last one, today. */
  selected: number | null
}

const cell = { py: 0.75, px: 0, fontSize: 13, border: 0 } as const

/** « Détail de l’énergie »: the chosen day's base, steps and workouts, and what they add up to. */
export const SuiviEnergyDetailCard = ({ state, today, selected }: SuiviEnergyDetailCardProps) => {
  const rows = state.status === 'ready' ? state.rows : []
  const row = selected === null ? rows.at(-1) : rows[selected]
  const values = row ? { base: row.bmrKcal, steps: row.stepsKcal, workouts: row.workoutKcal } : null

  return (
    <Box
      component="section"
      aria-label="Détail de l’énergie"
      sx={{ borderRadius: 2, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0, fontVariantNumeric: 'tabular-nums' }}
    >
      <Box sx={{ fontSize: 15, fontWeight: 500 }}>{row ? energyDayLabel(row, today) : 'Détail de l’énergie'}</Box>
      {state.status === 'ready' && row && values && (
        <Table size="small" sx={{ borderCollapse: 'collapse' }}>
          <TableBody>
            {PARTS.map((part) => (
              <TableRow key={part.key}>
                <TableCell sx={{ ...cell, color: 'text.secondary' }}>
                  <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
                    <Box component="span" sx={(theme) => ({ width: 8, height: 8, borderRadius: '2px', bgcolor: part.color(theme) })} />
                    {part.label}
                  </Box>
                </TableCell>
                <TableCell sx={{ ...cell, textAlign: 'right' }}>{kcal(values[part.key])}</TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell sx={{ ...cell, fontWeight: 500 }}>Dépensées</TableCell>
              <TableCell data-testid="energy-total" sx={{ ...cell, textAlign: 'right', fontWeight: 500 }}>
                {kcal(row.expendedKcal)}
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      )}
      {state.status === 'loading' && <Typography sx={(theme) => ({ fontSize: 12, color: theme.palette.veilleuse.caption })}>Chargement…</Typography>}
    </Box>
  )
}
