import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import { alpha, useTheme } from '@mui/material/styles'
import Typography from '@mui/material/Typography'
import { HomeCard } from '../home/HomeCard'
import { HomeCardError } from '../home/HomeCardError'
import { moduleColor } from '../home/moduleColor'
import { homeCardStatus, useReportHomeCard } from '../home/homeCardStatus'
import { neededLabel, quantityHint } from './groceryQuantity'
import { recipeNeedWhen } from './homeRecipeNeeds'
import { useGroceryCardState } from './useGroceryCardState'
import { groceryCountLabel } from './groceryCardState'
import type { GroceryHomeState, HomeStore } from './groceryCardState'

/**
 * The Courses tile of the Accueil (MAG-563): what the next meals lack, then the
 * open list store by store in a few tight lines. Read-only — a line is ticked in
 * Courses — and it does not follow the day selector, the list being today's.
 * Empty (no line, nothing missing for a recipe), it is not drawn at all (MAG-594).
 */
export const GroceryHomeCard = () => {
  const { state, loading, error, retry } = useGroceryCardState()
  const status = homeCardStatus({ state, loading, error }, isEmpty)
  useReportHomeCard('grocery', status)

  if (status === 'empty') return null

  return (
    <HomeCard module="grocery" title="Courses" to="/grocery">
      {state && !isEmpty(state) ? <Body state={state} /> : !error && loading ? <Loading /> : null}
      {error && <HomeCardError onRetry={() => void retry()} />}
    </HomeCard>
  )
}

const isEmpty = (state: GroceryHomeState) => state.count === 0 && state.forRecipes.length === 0

const Loading = () => (
  <Box aria-busy="true" data-testid="home-grocery-loading">
    <Skeleton width="60%" />
    <Skeleton width="80%" />
    <Skeleton width="50%" />
  </Box>
)

const Body = ({ state }: { state: GroceryHomeState }) => {
  const courses = moduleColor(useTheme(), 'grocery')

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      {state.count > 0 && (
        <Typography variant="caption" color="text.secondary" data-testid="home-grocery-count">
          {groceryCountLabel(state)}
        </Typography>
      )}
      {state.forRecipes.length > 0 && (
        <Box
          data-testid="home-grocery-recipes"
          sx={{ bgcolor: alpha(courses, 0.1), borderRadius: '14px', px: '10px', py: '8px' }}
        >
          <Box sx={{ fontSize: 11, letterSpacing: '0.08em', textTransform: 'uppercase', color: courses }}>
            Pour les recettes
          </Box>
          {state.forRecipes.map((need) => (
            <Box
              key={`${need.name}-${need.day}-${need.slot}`}
              data-testid="home-grocery-recipe-need"
              sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px', minHeight: 26 }}
            >
              <Typography noWrap sx={{ fontSize: 14, fontWeight: 500 }}>
                {need.name}
              </Typography>
              <Typography noWrap sx={{ fontSize: 12, color: courses, whiteSpace: 'nowrap' }}>
                {recipeNeedWhen(need)}
              </Typography>
            </Box>
          ))}
        </Box>
      )}
      {state.stores.map((store) => (
        <Store key={store.id ?? 'none'} store={store} />
      ))}
    </Box>
  )
}

const Store = ({ store }: { store: HomeStore }) => {
  const courses = moduleColor(useTheme(), 'grocery')

  return (
    <Box component="section" aria-label={store.name ?? 'Non assigné'} data-testid="home-grocery-store">
      <Typography
        variant="caption"
        component="h3"
        sx={{
          display: 'block',
          fontSize: 11,
          fontWeight: 400,
          letterSpacing: '0.08em',
          textTransform: 'uppercase',
          color: courses,
          pt: '4px',
        }}
      >
        {store.name ?? 'Non assigné'}
      </Typography>
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {store.items.map((item) => {
          const quantity = neededLabel(item.needed) ?? quantityHint(item.quantity, item.unit)

          return (
            <Box
              component="li"
              key={item.id}
              data-testid="home-grocery-item"
              sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 1, minHeight: 26 }}
            >
              <Typography noWrap sx={{ fontSize: 13 }}>
                {item.name}
              </Typography>
              {quantity && (
                <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
                  {quantity}
                </Typography>
              )}
            </Box>
          )
        })}
      </Box>
      {store.more > 0 && (
        <Typography variant="caption" color="text.secondary" data-testid="home-grocery-more">
          {`+ ${store.more}`}
        </Typography>
      )}
    </Box>
  )
}
