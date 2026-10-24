import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { ActivityLaunchButton } from '../home/ActivityLaunchButton'
import { moduleColor } from '../home/moduleColor'
import { HomeCard } from '../home/HomeCard'
import { HomeCardError } from '../home/HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../home/homeCardStatus'
import type { HomeDay } from '../home/homeDay'
import { useHomeCardState } from '../home/useHomeCardState'
import { mealLaunchPath } from './cooking/cookingSession'

interface HomeMeal {
  id: string
  title: string
  guests: number
  recipes: { id: string; name: string }[]
  missing: { id: string; name: string }[]
}

interface HomeSlot {
  slot: 'lunch' | 'dinner'
  meals: HomeMeal[]
}

interface NutritionHomeState {
  slots: HomeSlot[]
}

const SLOT_LABELS: Record<HomeSlot['slot'], string> = { lunch: 'Déjeuner', dinner: 'Dîner' }

/**
 * The Nutrition card of the Accueil (MAG-564): the meals of the chosen day, lunch
 * then dinner, with what the stock lacks for them. Read-only; the title opens the
 * week of meals (the admin has no other Menu view). With no meal planned that day
 * it is not drawn at all (MAG-594).
 */
export const NutritionHomeCard = ({ day }: { day: HomeDay }) => {
  const { state, loading, error, retry } = useHomeCardState<NutritionHomeState>('nutrition', day)
  const status = homeCardStatus({ state, loading, error }, isEmpty)
  useReportHomeCard('nutrition', status)

  if (status === 'empty') return null

  return (
    <HomeCard module="nutrition" title="Nutrition" to="/meals">
      {state && !isEmpty(state) ? <Body state={state} day={day} /> : !error && loading ? <Loading /> : null}
      {error && <HomeCardError onRetry={() => void retry()} />}
    </HomeCard>
  )
}

const isEmpty = (state: NutritionHomeState) => state.slots.every(({ meals }) => meals.length === 0)

const Loading = () => (
  <Box aria-busy="true" data-testid="home-nutrition-loading">
    <Skeleton width="60%" />
    <Skeleton width="80%" />
  </Box>
)

const Body = ({ state, day }: { state: NutritionHomeState; day: HomeDay }) => (
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
    {state.slots.map(({ slot, meals }) => (
      <Box key={slot} component="section" aria-label={SLOT_LABELS[slot]} data-testid={`home-nutrition-${slot}`}>
        <Typography variant="caption" color="text.secondary" component="h3" sx={{ display: 'block', fontWeight: 600 }}>
          {SLOT_LABELS[slot]}
        </Typography>
        {meals.length === 0 ? (
          <Typography variant="body2" color="text.secondary" data-testid="home-nutrition-empty">
            —
          </Typography>
        ) : (
          <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
            {meals.map((meal) => (
              <Meal key={meal.id} meal={meal} day={day} />
            ))}
          </Box>
        )}
      </Box>
    ))}
  </Box>
)

const Meal = ({ meal, day }: { meal: HomeMeal; day: HomeDay }) => {
  const names = meal.recipes.length > 0 ? meal.recipes.map((r) => r.name).join(' · ') : meal.title

  return (
    <Box component="li" data-testid="home-nutrition-meal" sx={{ py: 0.25 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 1 }}>
        <Typography variant="body2" data-testid="home-nutrition-recipes" sx={{ flex: 1, minWidth: 0 }}>
          {names}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }} data-testid="home-nutrition-guests">
          {`${meal.guests} pers.`}
        </Typography>
        {meal.recipes.length > 0 && <ActivityLaunchButton module="nutrition" activity={`la cuisine de ${names}`} day={day} to={mealLaunchPath(meal.id)} />}
      </Box>
      {meal.missing.length > 0 && (
        <Typography
          variant="caption"
          data-testid="home-nutrition-missing"
          sx={(theme) => ({ display: 'block', color: moduleColor(theme, 'grocery') })}
        >
          {`manque : ${meal.missing.map((m) => m.name).join(', ')}`}
        </Typography>
      )}
    </Box>
  )
}
