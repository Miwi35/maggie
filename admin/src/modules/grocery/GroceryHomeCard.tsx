import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import { HomeCard } from '../home/HomeCard'
import { HomeCardError } from '../home/HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../home/homeCardStatus'
import { neededLabel, quantityHint } from './groceryQuantity'
import { recipeNeedsLabel } from './homeRecipeNeeds'
import { useGroceryCardState } from './useGroceryCardState'
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
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
      {state.forRecipes.length > 0 && (
        <Typography variant="body2" data-testid="home-grocery-recipes">
          <Box component="span" sx={{ fontWeight: 600 }}>
            Pour les recettes
          </Box>
          {' · '}
          {recipeNeedsLabel(state.forRecipes)}
        </Typography>
      )}
      {state.stores.map((store) => (
        <Store key={store.id ?? 'none'} store={store} />
      ))}
    </Box>
  )
}

const Store = ({ store }: { store: HomeStore }) => (
  <Box component="section" aria-label={store.name ?? 'Non assigné'} data-testid="home-grocery-store">
    <Typography variant="caption" color="text.secondary" component="h3" sx={{ display: 'block', fontWeight: 600 }}>
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
            sx={{ display: 'flex', justifyContent: 'space-between', gap: 1, py: 0.25 }}
          >
            <Typography variant="body2" noWrap>
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
