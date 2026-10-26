import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import ListItemButton from '@mui/material/ListItemButton'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import { useCompactScreen } from '../../../hooks/useCompactScreen'
import { NO_AISLE } from '../../grocery/groceryAisles'
import { isPackaged, lineUnit, neededLabel, quantityHint } from '../../grocery/groceryQuantity'
import { needOf, shortfallOf } from './cookingGrocery'
import type { CookingGroceryItem, CookingStore, MealNeeds } from './cookingGrocery'
import type { CookingGrocery } from './useCookingGrocery'

const TOUCH_TARGET = 64

/** « Courses 3 »: the lines still to buy; no number while the list is not known. */
export const CookingGroceryButton = ({ count, open, onClick }: { count: number | null; open: boolean; onClick: () => void }) => (
  <Button
    variant={open ? 'contained' : 'outlined'}
    color="primary"
    startIcon={<ShoppingCartIcon />}
    aria-haspopup="dialog"
    aria-expanded={open}
    onClick={onClick}
    sx={{ minHeight: TOUCH_TARGET, px: 2.5, fontSize: '1.125rem', whiteSpace: 'nowrap' }}
  >
    Courses
    {count !== null && (
      <Box component="span" data-testid="cooking-grocery-count" sx={{ ml: 1, fontWeight: 700 }}>
        {count}
      </Box>
    )}
  </Button>
)

const quantityOf = (item: CookingGroceryItem): string | null =>
  neededLabel(item.needed) ?? quantityHint(item.quantity, isPackaged(item.product) ? lineUnit(item.product) : item.unit)

const Line = ({ item, needs, onToggle }: { item: CookingGroceryItem; needs: MealNeeds; onToggle: (item: CookingGroceryItem) => void }) => {
  const need = needOf(item, needs)
  const missing = need ? shortfallOf(item, need) : null
  const listed = quantityOf(item)
  const quantity = need ? neededLabel(need) : listed

  return (
    <ListItemButton
      component="div"
      data-testid="cooking-grocery-item"
      data-meal={need ? 'true' : 'false'}
      onClick={() => onToggle(item)}
      sx={{ minHeight: TOUCH_TARGET, gap: 1, px: 2 }}
    >
      <Checkbox
        edge="start"
        checked={item.checked}
        tabIndex={-1}
        disableRipple
        inputProps={{ 'aria-label': item.label }}
        sx={{ p: 1.5, '& .MuiSvgIcon-root': { fontSize: 32 } }}
      />
      <Box sx={{ flex: 1, minWidth: 0, textDecoration: item.checked ? 'line-through' : 'none', opacity: item.checked ? 0.5 : 1 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          <Typography sx={{ fontSize: '1.125rem' }}>{item.label}</Typography>
          {need && <Chip label="ce repas" size="small" color="primary" variant="outlined" />}
        </Box>
        {missing && (
          <Typography variant="body2" color="warning.main" data-testid="cooking-grocery-missing">
            {listed ? `${listed} sur la liste · ` : ''}il en faut {neededLabel([missing])}
          </Typography>
        )}
      </Box>
      {quantity && (
        <Typography color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
          {quantity}
        </Typography>
      )}
    </ListItemButton>
  )
}

const Heading = ({ children, small = false }: { children: string; small?: boolean }) => (
  <Typography
    variant={small ? 'caption' : 'overline'}
    color="text.secondary"
    component="h3"
    sx={{ display: 'block', px: 2, pt: small ? 1 : 2, pb: 0.5, fontWeight: 600 }}
  >
    {children}
  </Typography>
)

const Store = ({ store, needs, onToggle }: { store: CookingStore; needs: MealNeeds; onToggle: (item: CookingGroceryItem) => void }) => {
  const aisled = store.sections.some((section) => section.aisle !== null)

  return (
    <Box component="section" data-testid="cooking-grocery-store" aria-label={store.name}>
      <Heading>{store.name}</Heading>
      {store.meal.map((item) => (
        <Line key={item.id} item={item} needs={needs} onToggle={onToggle} />
      ))}
      {store.sections.map((section) => (
        <Box key={section.aisle?.id ?? 'none'}>
          {aisled && <Heading small>{section.aisle?.name ?? NO_AISLE}</Heading>}
          {section.items.map((item) => (
            <Line key={item.id} item={item} needs={needs} onToggle={onToggle} />
          ))}
        </Box>
      ))}
    </Box>
  )
}

interface CookingGroceryPanelProps {
  open: boolean
  onClose: () => void
  grocery: CookingGrocery
  stores: CookingStore[]
  needs: MealNeeds
  count: number
}

/**
 * The shopping list inside the activity (MAG-583): a panel on the right of a tablet, a sheet from
 * the bottom of a phone held upright. It is an overlay beside the step, which stays mounted under it.
 */
export const CookingGroceryPanel = ({ open, onClose, grocery, stores, needs, count }: CookingGroceryPanelProps) => {
  const compact = useCompactScreen()

  return (
    <Drawer
      anchor={compact ? 'bottom' : 'right'}
      open={open}
      onClose={onClose}
      PaperProps={{
        role: 'dialog',
        'aria-label': 'Liste de courses',
        sx: { width: compact ? '100%' : 'min(440px, 100vw)', maxHeight: compact ? '85dvh' : '100%', display: 'flex', flexDirection: 'column' },
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, pl: 2, pr: 1, py: 1, borderBottom: 1, borderColor: 'divider' }}>
        <Typography variant="h6" component="h2" sx={{ flex: 1 }}>
          Courses
          {grocery.load === 'ready' && count > 0 && (
            <Typography component="span" color="text.secondary" sx={{ ml: 1 }}>
              {count} à acheter
            </Typography>
          )}
        </Typography>
        <IconButton aria-label="Fermer la liste de courses" onClick={onClose} sx={{ width: TOUCH_TARGET, height: TOUCH_TARGET }}>
          <CloseIcon />
        </IconButton>
      </Box>

      {grocery.notice && (
        <Alert severity="error" onClose={grocery.clearNotice} data-testid="cooking-grocery-notice" sx={{ m: 1 }}>
          {grocery.notice}
        </Alert>
      )}

      <Box data-testid="cooking-grocery-list" sx={{ flex: 1, minHeight: 0, overflowY: 'auto', pb: 2 }}>
        {grocery.load === 'loading' && (
          <Box data-testid="cooking-grocery-loading" sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
            <CircularProgress aria-label="Chargement de la liste" />
          </Box>
        )}
        {grocery.load === 'error' && (
          <Alert
            severity="error"
            sx={{ m: 2 }}
            action={
              <Button color="inherit" onClick={grocery.retry} sx={{ minHeight: TOUCH_TARGET }}>
                Réessayer
              </Button>
            }
          >
            La liste de courses n'a pas pu être chargée.
          </Alert>
        )}
        {grocery.load === 'ready' && stores.length === 0 && (
          <Typography data-testid="cooking-grocery-empty" color="text.secondary" sx={{ p: 4, textAlign: 'center', fontSize: '1.25rem' }}>
            Rien à acheter
          </Typography>
        )}
        {grocery.load === 'ready' && stores.map((store) => <Store key={store.key} store={store} needs={needs} onToggle={grocery.toggle} />)}
      </Box>
    </Drawer>
  )
}
