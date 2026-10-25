import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Paper from '@mui/material/Paper'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import CloseIcon from '@mui/icons-material/Close'
import DeleteIcon from '@mui/icons-material/Delete'
import RemoveIcon from '@mui/icons-material/Remove'
import ShoppingBasketIcon from '@mui/icons-material/ShoppingBasket'
import { quantityLabel } from '../grocery/packaging'
import { GuestsStepper, MAX_GUESTS } from './GuestsStepper'
import { idOf } from './recipeSheet'
import { FALLBACK_GUESTS, iriOf, mealRecipesOf, slotLabel } from './mealsWeek'
import type { Meal, MealRecipe, Recipe } from './mealsWeek'
import { useScaledQuantities } from './useScaledQuantities'

export interface MealActions {
  /** `null` gives the meal back to the default guests. */
  onGuests: (meal: Meal, guests: number | null) => void
  onRecipes: (meal: Meal, iris: string[]) => void
  onDelete: (meal: Meal) => void
  onReview: (meal: Meal) => void
}

/** The meal as its screen-reader names are written: « Déjeuner : Tartiflette ». */
export const mealTitle = (meal: Meal): string => meal.summary || slotLabel(meal.slot)

const people = (n: number) => `${n} personne${n > 1 ? 's' : ''}`

function RecipeLine({ entry, meal, name, disabled, onRemove }: { entry: MealRecipe; meal: Meal; name: string; disabled: boolean; onRemove: () => void }) {
  const guests = meal.guests ?? FALLBACK_GUESTS
  // A simulation on the screen: nothing is saved, and it follows the meal's guests until it is touched.
  const [chosen, setChosen] = useState<number | null>(null)
  const [open, setOpen] = useState(false)
  const portions = chosen ?? guests
  const servings = entry.recipe?.servings
  const differs = servings != null && portions !== servings
  const scaled = useScaledQuantities(open && differs ? entry.id : null, servings ?? portions, portions)

  return (
    <Box data-testid={`recipe-${entry.id}`}>
      <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', columnGap: 1 }}>
        <Typography sx={{ flex: '1 1 8rem', minWidth: 0, overflowWrap: 'anywhere' }}>{name}</Typography>
        <IconButton size="small" aria-label={`Une portion de moins — ${name}`} disabled={portions <= 1} onClick={() => setChosen(portions - 1)}>
          <RemoveIcon fontSize="small" />
        </IconButton>
        <Typography component="span" variant="body2" sx={{ whiteSpace: 'nowrap' }}>
          <span data-testid="portions-value">{portions}</span> {portions > 1 ? 'portions' : 'portion'}
        </Typography>
        <IconButton size="small" aria-label={`Une portion de plus — ${name}`} disabled={portions >= MAX_GUESTS} onClick={() => setChosen(portions + 1)}>
          <AddIcon fontSize="small" />
        </IconButton>
        <IconButton size="small" aria-label={`Retirer ${name} du repas`} disabled={disabled} onClick={onRemove}>
          <CloseIcon fontSize="small" />
        </IconButton>
      </Box>
      {differs && (
        <Box sx={{ pl: 1 }}>
          <Button size="small" aria-expanded={open} onClick={() => setOpen(!open)} sx={{ px: 0.5, py: 0 }}>
            {`Quantités pour ${people(portions)}`}
          </Button>
          {open && scaled.state === 'loading' && <Typography variant="body2" color="text.secondary">Calcul des quantités…</Typography>}
          {open && scaled.state === 'failed' && (
            <Alert severity="error" sx={{ py: 0 }}>
              Les quantités n’ont pas pu être calculées
            </Alert>
          )}
          {open && scaled.state === 'ready' && (
            <Box component="ul" sx={{ m: 0, pl: 3 }}>
              {scaled.lines.map((line, index) => (
                <Typography key={line.id ?? index} component="li" variant="body2">
                  {`${quantityLabel(line.quantity, line.unit)} ${line.ingredientName ?? ''}`.trim()}
                </Typography>
              ))}
            </Box>
          )}
        </Box>
      )}
    </Box>
  )
}

function AddRecipe({ options, onPick }: { options: Recipe[]; onPick: (recipe: Recipe) => void }) {
  const [adding, setAdding] = useState(false)

  if (!adding) {
    return (
      <Button size="small" sx={{ alignSelf: 'flex-start' }} onClick={() => setAdding(true)}>
        + Recette
      </Button>
    )
  }

  return (
    <Autocomplete
      size="small"
      openOnFocus
      blurOnSelect
      options={options}
      getOptionLabel={(option) => option.name}
      isOptionEqualToValue={(option, value) => option.id === value.id}
      noOptionsText="Aucune autre recette"
      onChange={(_, value) => {
        setAdding(false)
        if (value) onPick(value)
      }}
      onBlur={() => setAdding(false)}
      renderInput={(params) => <TextField {...params} autoFocus label="Recette à ajouter" />}
    />
  )
}

export function MealCard({
  meal,
  recipes,
  known,
  busy,
  actions,
}: {
  meal: Meal
  /** Every recipe the owner has, to add one. */
  recipes: Recipe[]
  known: Map<string, Recipe>
  busy: boolean
  actions: MealActions
}) {
  const entries = mealRecipesOf(meal, known)
  const title = mealTitle(meal)
  // A recipe the list does not know is named by the meal's summary when it is the only one.
  const nameOf = (entry: MealRecipe) => entry.recipe?.name ?? (entries.length === 1 && meal.summary ? meal.summary : 'Recette')
  const guests = meal.guests ?? FALLBACK_GUESTS
  const have = new Set(entries.map((entry) => entry.id))

  const remove = (entry: MealRecipe) => {
    const remaining = entries.filter((other) => other !== entry).map((other) => other.iri)
    if (remaining.length === 0) actions.onDelete(meal)
    else actions.onRecipes(meal, remaining)
  }

  return (
    <Paper data-testid={`meal-${meal.id}`} variant="outlined" sx={{ p: 1.5, display: 'flex', flexDirection: 'column', gap: 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center' }}>
        <Typography variant="subtitle2" sx={{ flex: 1 }}>
          {slotLabel(meal.slot)}
        </Typography>
        {/* A meal puts nothing on the list by itself (MAG-300): this reopens the choice. */}
        <IconButton
          size="small"
          aria-label={`Revoir les ingrédients à acheter pour ${title}`}
          title="Revoir les ingrédients à acheter"
          disabled={busy}
          onClick={() => actions.onReview(meal)}
        >
          <ShoppingBasketIcon fontSize="small" />
        </IconButton>
        <IconButton size="small" aria-label={`Supprimer le repas ${title}`} disabled={busy} onClick={() => actions.onDelete(meal)}>
          <DeleteIcon fontSize="small" />
        </IconButton>
      </Box>
      {entries.map((entry) => (
        <RecipeLine key={entry.iri} entry={entry} meal={meal} name={nameOf(entry)} disabled={busy} onRemove={() => remove(entry)} />
      ))}
      <AddRecipe options={recipes.filter((recipe) => !have.has(idOf(recipe['@id'] ?? recipe.id)))} onPick={(recipe) => actions.onRecipes(meal, [...entries.map((entry) => entry.iri), iriOf('recipes', recipe)])} />
      <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
        <GuestsStepper
          label="Convives"
          name={`Convives du repas ${title}`}
          value={guests}
          isDefault={meal.guestsIsDefault === true}
          disabled={busy}
          onChange={(value) => actions.onGuests(meal, value)}
        />
        {meal.guestsIsDefault === false && (
          <Button size="small" disabled={busy} onClick={() => actions.onGuests(meal, null)}>
            Revenir au défaut
          </Button>
        )}
      </Box>
    </Paper>
  )
}
