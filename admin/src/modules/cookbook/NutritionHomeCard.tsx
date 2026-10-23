import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { moduleColor } from '../home/moduleColor'
import { HomeCard } from '../home/HomeCard'
import { HomeCardError } from '../home/HomeCardError'
import type { HomeDay } from '../home/homeDay'
import { useHomeCardState } from '../home/useHomeCardState'

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
 * week of meals (the admin has no other Menu view).
 */
export const NutritionHomeCard = ({ day }: { day: HomeDay }) => {
  const { state, loading, error, retry } = useHomeCardState<NutritionHomeState>('nutrition', day)

  return (
    <HomeCard module="nutrition" title="Nutrition" to="/meals">
      {state ? <Body state={state} /> : error ? <HomeCardError onRetry={() => void retry()} /> : loading ? <Loading /> : null}
      {state && error && <HomeCardError onRetry={() => void retry()} />}
    </HomeCard>
  )
}

const Loading = () => (
  <Box aria-busy="true" data-testid="home-nutrition-loading">
    <Skeleton width="60%" />
    <Skeleton width="80%" />
  </Box>
)

const Body = ({ state }: { state: NutritionHomeState }) => (
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
              <Meal key={meal.id} meal={meal} />
            ))}
          </Box>
        )}
      </Box>
    ))}
  </Box>
)

const Meal = ({ meal }: { meal: HomeMeal }) => {
  const names = meal.recipes.length > 0 ? meal.recipes.map((r) => r.name).join(' · ') : meal.title

  return (
    <Box component="li" data-testid="home-nutrition-meal" sx={{ py: 0.25 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1 }}>
        <Typography variant="body2" data-testid="home-nutrition-recipes">
          {names}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }} data-testid="home-nutrition-guests">
          {`${meal.guests} pers.`}
        </Typography>
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
