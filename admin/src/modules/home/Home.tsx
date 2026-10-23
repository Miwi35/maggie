import Box from '@mui/material/Box'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import { cardRegistry } from './cardRegistry'
import { HOME_DAYS, useHomeDay } from './homeDay'
import type { HomeDay } from './homeDay'
import type { HomeCardEntry, HomeZone } from './cardRegistry'

const sx = {
  stack: { display: 'flex', flexDirection: 'column', gap: 2 },
} as const

/**
 * The Accueil: the day selector, then the cards of the registry by zone — the
 * strip of actions, then the left column (Tâches, Agenda) beside the grid of the
 * other modules. A zone without a card is not drawn, so an empty registry leaves
 * the selector alone.
 *
 * Two columns from `lg`, not `md`: beside the 240px menu and the 380px chat, a
 * 900px window leaves the page no more than 280px.
 */
export const Home = ({ registry = cardRegistry }: { registry?: HomeCardEntry[] }) => {
  const [day, setDay] = useHomeDay()

  const zone = (name: HomeZone) =>
    registry
      .filter((entry) => entry.zone === name)
      .sort((a, b) => a.order - b.order)
      .map((entry, index) => (
        <Box key={`${entry.module}-${index}`} sx={{ minWidth: 0 }}>
          {entry.render({ day })}
        </Box>
      ))
  const action = zone('action')
  const left = zone('left')
  const grid = zone('grid')

  return (
    <Box sx={(theme) => ({ ...sx.stack, gap: 3, p: 3, maxWidth: 1200, mx: 'auto', width: '100%', [theme.breakpoints.down('md')]: { p: 2 } })}>
      <DaySelector day={day} onChange={setDay} />
      {action.length > 0 && <Box sx={sx.stack}>{action}</Box>}
      {(left.length > 0 || grid.length > 0) && (
        <Box
          sx={(theme) => ({
            display: 'grid',
            gap: 2,
            gridTemplateColumns: 'minmax(0, 1fr)',
            alignItems: 'start',
            [theme.breakpoints.up('lg')]: {
              gridTemplateColumns: left.length > 0 && grid.length > 0 ? 'repeat(2, minmax(0, 1fr))' : 'minmax(0, 1fr)',
            },
          })}
        >
          {left.length > 0 && <Box sx={sx.stack}>{left}</Box>}
          {grid.length > 0 && (
            <Box
              sx={{
                display: 'grid',
                gap: 2,
                gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))',
                alignItems: 'start',
              }}
            >
              {grid}
            </Box>
          )}
        </Box>
      )}
    </Box>
  )
}

const DaySelector = ({ day, onChange }: { day: HomeDay; onChange: (day: HomeDay) => void }) => (
  <ToggleButtonGroup
    exclusive
    size="small"
    value={day}
    aria-label="Jour"
    // Pressing the selected button again would unselect it: the day stays.
    onChange={(_, next: HomeDay | null) => next && onChange(next)}
    sx={(theme) => ({ alignSelf: 'flex-start', [theme.breakpoints.down('md')]: { alignSelf: 'stretch' } })}
  >
    {HOME_DAYS.map(({ value, label }) => (
      <ToggleButton
        key={value}
        value={value}
        sx={(theme) => ({ textTransform: 'none', px: 2, [theme.breakpoints.down('md')]: { minHeight: 48, flex: 1 } })}
      >
        {label}
      </ToggleButton>
    ))}
  </ToggleButtonGroup>
)
