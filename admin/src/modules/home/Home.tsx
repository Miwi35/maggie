import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { cardRegistry } from './cardRegistry'
import { HomeCardStatusContext } from './homeCardStatus'
import type { HomeCardStatus } from './homeCardStatus'
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
 * A card with nothing to show for the chosen day says so (`empty`): it draws nothing,
 * its place closes up, and when every card is empty the page says one calm sentence
 * instead (MAG-594). A card still loading or in error is not empty.
 *
 * Two columns from `lg`, not `md`: beside the 240px menu and the 380px chat, a
 * 900px window leaves the page no more than 280px.
 */
export const Home = ({ registry = cardRegistry }: { registry?: HomeCardEntry[] }) => {
  const [day, setDay] = useHomeDay()
  const [statuses, setStatuses] = useState<Readonly<Record<string, HomeCardStatus>>>({})

  const report = useCallback((module: string, status: HomeCardStatus | null) => {
    setStatuses((current) => {
      if ((current[module] ?? null) === status) return current
      const next = { ...current }
      if (status === null) delete next[module]
      else next[module] = status

      return next
    })
  }, [])

  const isEmpty = (entry: HomeCardEntry) => statuses[entry.module] === 'empty'
  // An empty card stays mounted, so it reads again and comes back when its module fills up; only its box is closed.
  const zone = (name: HomeZone) => {
    const entries = registry.filter((entry) => entry.zone === name).sort((a, b) => a.order - b.order)

    return {
      shown: entries.filter((entry) => !isEmpty(entry)).length,
      nodes: entries.map((entry, index) => (
        <Box key={`${entry.module}-${index}`} sx={{ minWidth: 0, ...(isEmpty(entry) && { display: 'none' }) }}>
          {entry.render({ day })}
        </Box>
      )),
    }
  }
  const actionZone = zone('action')
  const leftZone = zone('left')
  const gridZone = zone('grid')
  const action = actionZone.nodes
  const left = leftZone.nodes
  const grid = gridZone.nodes
  const allEmpty = registry.length > 0 && registry.every(isEmpty)

  return (
    <HomeCardStatusContext.Provider value={report}>
      <Box sx={(theme) => ({ ...sx.stack, gap: 3, p: 3, maxWidth: 1200, mx: 'auto', width: '100%', [theme.breakpoints.down('md')]: { p: 2 } })}>
        <DaySelector day={day} onChange={setDay} />
        {allEmpty && (
          <Typography variant="body1" color="text.secondary" data-testid="home-empty">
            {day === 'tomorrow' ? 'Rien de prévu demain.' : "Rien de prévu aujourd'hui."}
          </Typography>
        )}
        {action.length > 0 && <Box sx={{ ...sx.stack, ...(actionZone.shown === 0 && { display: 'none' }) }}>{action}</Box>}
        {(left.length > 0 || grid.length > 0) && (
          <Box
            sx={(theme) => ({
              display: 'grid',
              gap: 2,
              gridTemplateColumns: 'minmax(0, 1fr)',
              alignItems: 'start',
              [theme.breakpoints.up('lg')]: {
                gridTemplateColumns: leftZone.shown > 0 && gridZone.shown > 0 ? 'repeat(2, minmax(0, 1fr))' : 'minmax(0, 1fr)',
              },
            })}
          >
            {left.length > 0 && <Box sx={{ ...sx.stack, ...(leftZone.shown === 0 && { display: 'none' }) }}>{left}</Box>}
            {grid.length > 0 && (
              <Box
                sx={{
                  display: gridZone.shown === 0 ? 'none' : 'grid',
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
    </HomeCardStatusContext.Provider>
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
