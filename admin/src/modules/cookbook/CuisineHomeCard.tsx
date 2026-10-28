import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
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
import type { HomeMeal, HomeSlot, CuisineHomeState } from './cuisineCardState'

const SLOT_LABELS: Record<HomeSlot['slot'], string> = { lunch: 'Déjeuner', dinner: 'Dîner' }

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
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
    {state.slots.map(({ slot, meals }) => (
      <Box key={slot} component="section" aria-label={SLOT_LABELS[slot]} data-testid={`home-cuisine-${slot}`}>
        <Typography variant="caption" color="text.secondary" component="h3" sx={{ display: 'block', fontWeight: 600 }}>
          {SLOT_LABELS[slot]}
        </Typography>
        {meals.length === 0 ? (
          <Typography variant="body2" color="text.secondary" data-testid="home-cuisine-empty">
            —
          </Typography>
        ) : (
          <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
            {meals.map((meal) => (
              <Meal key={meal.id} meal={meal} day={day} status={status} />
            ))}
          </Box>
        )}
      </Box>
    ))}
  </Box>
  )
}

const Meal = ({ meal, day, status }: { meal: HomeMeal; day: HomeDay; status: CookingStatus | null }) => {
  const names = meal.recipes.length > 0 ? meal.recipes.join(' · ') : meal.title

  return (
    <Box component="li" data-testid="home-cuisine-meal" sx={{ py: 0.25 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 1 }}>
        <Typography variant="body2" data-testid="home-cuisine-recipes" sx={{ flex: 1, minWidth: 0 }}>
          {names}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }} data-testid="home-cuisine-guests">
          {`${meal.guests} pers.`}
        </Typography>
        {meal.recipes.length > 0 && <ActivityLaunchButton module="cuisine" activity={`la cuisine de ${names}`} day={day} to={mealLaunchPath(meal.id)} resume={resumesMeal(status, meal.id)} />}
      </Box>
      {meal.missing.length > 0 && (
        <Typography
          variant="caption"
          data-testid="home-cuisine-missing"
          sx={(theme) => ({ display: 'block', color: moduleColor(theme, 'grocery') })}
        >
          {`manque : ${meal.missing.join(', ')}`}
        </Typography>
      )}
    </Box>
  )
}
