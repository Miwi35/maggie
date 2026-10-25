import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { cardRegistry } from './cardRegistry'
import type { CardRegistry } from './cardRegistry'
import homeConfig from './home.config'
import type { HomeCardConfig, HomeConfig } from './home.config'
import { HomeCardStatusContext } from './homeCardStatus'
import type { HomeCardStatus } from './homeCardStatus'
import { HOME_DAYS, useHomeDay } from './homeDay'
import type { HomeDay } from './homeDay'

const sx = {
  stack: { display: 'flex', flexDirection: 'column', gap: 2 },
} as const

/** The page is wide enough for the left column beside the grid. */
const WIDE = '@container home (min-width: 880px)'

/**
 * The Accueil: the day selector, then the cards of the config (`home.config.ts`) by
 * zone — the strip of launchers, then the left column (Tâches, Agenda) beside the
 * grid of the other modules. A card disabled in the config, or with no component in
 * the registry, is skipped; a zone without a card is not drawn, so an empty config
 * leaves the selector alone.
 *
 * A card with nothing to show for the chosen day says so (`empty`): it draws nothing,
 * its place closes up, and when every card is empty the page says one calm sentence
 * instead (MAG-594). A card still loading or in error is not empty.
 *
 * Full page (`accueil/Accueil-Admin`, MAG-625): the left column beside the grid of the other
 * modules, which has two columns of its own. The widths are the page's, not the window's — a
 * container query — since the menu and the open chat panel take 620px of a 1440px window.
 */
export const Home = ({ config = homeConfig, registry = cardRegistry }: { config?: HomeConfig; registry?: CardRegistry }) => {
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

  const isEmpty = (card: HomeCardConfig) => statuses[card.key] === 'empty'
  const shownCards = (cards: HomeCardConfig[] = []) => cards.filter((card) => card.enabled !== false && registry[card.key])
  // An empty card stays mounted, so it reads again and comes back when its module fills up; only its box is closed.
  const zone = (cards: HomeCardConfig[]) => ({
    shown: cards.filter((card) => !isEmpty(card)).length,
    nodes: cards.map((card, index) => {
      const Card = registry[card.key]

      return (
        <Box key={`${card.key}-${index}`} sx={{ minWidth: 0, ...(isEmpty(card) && { display: 'none' }) }}>
          {Card && <Card day={day} />}
        </Box>
      )
    }),
  })
  const pageCards = shownCards(config.card)
  const launchCards = shownCards(config.launch)
  const actionZone = zone(launchCards)
  const leftZone = zone(pageCards.filter((card) => card.column === 'left'))
  const gridZone = zone(pageCards.filter((card) => card.column !== 'left'))
  const action = actionZone.nodes
  const left = leftZone.nodes
  const grid = gridZone.nodes
  const allCards = [...launchCards, ...pageCards]
  const allEmpty = allCards.length > 0 && allCards.every(isEmpty)

  return (
    <HomeCardStatusContext.Provider value={report}>
      <Box sx={{ containerType: 'inline-size', containerName: 'home', width: '100%', minWidth: 0 }}>
      <Box sx={(theme) => ({ ...sx.stack, gap: 3, p: 3, [theme.breakpoints.down('md')]: { p: 2 } })}>
        <DaySelector day={day} onChange={setDay} />
        {allEmpty && (
          <Typography variant="body1" color="text.secondary" data-testid="home-empty">
            {day === 'tomorrow' ? 'Rien de prévu demain.' : "Rien de prévu aujourd'hui."}
          </Typography>
        )}
        {action.length > 0 && <Box sx={{ ...sx.stack, ...(actionZone.shown === 0 && { display: 'none' }) }}>{action}</Box>}
        {(left.length > 0 || grid.length > 0) && (
          <Box
            sx={{
              display: 'grid',
              gap: 2,
              gridTemplateColumns: 'minmax(0, 1fr)',
              alignItems: 'start',
              [WIDE]: {
                gridTemplateColumns: leftZone.shown > 0 && gridZone.shown > 0 ? 'minmax(280px, 1fr) minmax(0, 2fr)' : 'minmax(0, 1fr)',
              },
            }}
          >
            {left.length > 0 && <Box sx={{ ...sx.stack, ...(leftZone.shown === 0 && { display: 'none' }) }}>{left}</Box>}
            {grid.length > 0 && (
              <Box
                sx={{
                  display: gridZone.shown === 0 ? 'none' : 'grid',
                  gap: 2,
                  gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))',
                  alignItems: 'start',
                  [WIDE]: { gridTemplateColumns: leftZone.shown > 0 ? 'repeat(2, minmax(0, 1fr))' : 'repeat(auto-fill, minmax(240px, 1fr))' },
                }}
              >
                {grid}
              </Box>
            )}
          </Box>
        )}
      </Box>
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
