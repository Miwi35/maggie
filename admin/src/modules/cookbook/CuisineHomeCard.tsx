import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import Typography from '@mui/material/Typography'
import { ActivityLaunchButton } from '../home/ActivityLaunchButton'
import { moduleColor } from '../home/moduleColor'
import { HomeCard } from '../home/HomeCard'
import { HomeCardError } from '../home/HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../home/homeCardStatus'
import type { HomeDay } from '../home/homeDay'
import { mealLaunchPath } from './cooking/cookingSession'
import { resumesMeal, useCookingStatus } from './cooking/cookingStatus'
import type { CookingStatus } from './cooking/cookingStatusApi'
import { useCuisineCardState } from './useCuisineCardState'
import { mealHeadline, mealRecipesLine, missingLabel } from './cuisineCardState'
import type { HomeMeal, HomeSlot, CuisineHomeState } from './cuisineCardState'

const SLOT_LABELS: Record<HomeSlot['slot'], string> = { lunch: 'Midi', dinner: 'Soir' }

/**
 * The Cuisine card of the Accueil (MAG-564): the meals of the chosen day, lunch
 * then dinner, with what the stock lacks for them, read from the meals and recipe
 * needs resources (MAG-591). Read-only; the title opens the week of meals (the
 * admin has no other Menu view). With no meal planned that day it is not drawn at
 * all (MAG-594).
 */
export const CuisineHomeCard = ({ day }: { day: HomeDay }) => {
  const { state, loading, error, retry } = useCuisineCardState(day)
  const status = homeCardStatus({ state, loading, error }, isEmpty)
  useReportHomeCard('cuisine', status)

  if (status === 'empty') return null

  return (
    <HomeCard module="cuisine" title="Cuisine" to="/meals">
      {state && !isEmpty(state) ? <Body state={state} day={day} /> : !error && loading ? <Loading /> : null}
      {error && <HomeCardError onRetry={() => void retry()} />}
    </HomeCard>
  )
}

const isEmpty = (state: CuisineHomeState) => state.slots.every(({ meals }) => meals.length === 0)

const Loading = () => (
  <Box aria-busy="true" data-testid="home-cuisine-loading">
    <Skeleton width="60%" />
    <Skeleton width="80%" />
  </Box>
)

const Body = ({ state, day }: { state: CuisineHomeState; day: HomeDay }) => {
  const { status } = useCookingStatus()

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column' }}>
      {state.slots.map(({ slot, meals }) => (
        <Box key={slot} component="section" aria-label={SLOT_LABELS[slot]} data-testid={`home-cuisine-${slot}`}>
          {meals.length === 0 ? (
            <Box sx={rowSx}>
              <SlotLabel label={SLOT_LABELS[slot]} />
              <Typography variant="body2" color="text.secondary" data-testid="home-cuisine-empty" sx={{ gridColumn: '2 / -1' }}>
                —
              </Typography>
            </Box>
          ) : (
            <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
              {meals.map((meal, index) => (
                <Meal key={meal.id} meal={meal} day={day} status={status} slotLabel={index === 0 ? SLOT_LABELS[slot] : null} />
              ))}
            </Box>
          )}
        </Box>
      ))}
    </Box>
  )
}

const rowSx = (theme: Theme) => ({
  display: 'grid',
  gridTemplateColumns: '44px minmax(0, 1fr) auto auto',
  gap: '10px',
  alignItems: 'center',
  pt: '8px',
  pb: '8px',
  borderTop: `1px solid ${theme.palette.divider}`,
})

const SlotLabel = ({ label }: { label: string | null }) => (
  <Typography component="span" sx={(theme) => ({ fontSize: 12, color: theme.palette.veilleuse.caption })}>
    {label}
  </Typography>
)

const Meal = ({ meal, day, status, slotLabel }: { meal: HomeMeal; day: HomeDay; status: CookingStatus | null; slotLabel: string | null }) => {
  const recipesLine = mealRecipesLine(meal)
  const missing = missingLabel(meal)

  return (
    <Box component="li" data-testid="home-cuisine-meal" sx={rowSx}>
      <SlotLabel label={slotLabel} />
      <Box sx={{ minWidth: 0, display: 'flex', flexDirection: 'column', gap: '2px' }}>
        <Typography noWrap data-testid="home-cuisine-name" sx={{ fontSize: 14, fontWeight: 500 }}>
          {mealHeadline(meal)}
        </Typography>
        {recipesLine && (
          <Typography noWrap data-testid="home-cuisine-recipes" sx={(theme) => ({ fontSize: 12, color: theme.palette.veilleuse.caption })}>
            {recipesLine}
          </Typography>
        )}
        {missing && (
          <Box
            data-testid="home-cuisine-missing"
            sx={(theme) => {
              const courses = moduleColor(theme, 'grocery')

              return {
                display: 'inline-flex',
                alignItems: 'center',
                alignSelf: 'flex-start',
                gap: '5px',
                height: 22,
                px: '8px',
                borderRadius: 999,
                bgcolor: alpha(courses, 0.12),
                color: courses,
                fontSize: 12,
                fontWeight: 500,
                whiteSpace: 'nowrap',
              }
            }}
          >
            <ShoppingCartIcon sx={{ fontSize: 12 }} />
            {missing}
          </Box>
        )}
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }} data-testid="home-cuisine-guests">
        {`${meal.guests} pers.`}
      </Typography>
      {meal.recipes.length > 0 ? (
        <ActivityLaunchButton module="cuisine" activity={`la cuisine de ${meal.recipes.join(' · ')}`} day={day} to={mealLaunchPath(meal.id)} resume={resumesMeal(status, meal.id)} />
      ) : (
        <span />
      )}
    </Box>
  )
}
