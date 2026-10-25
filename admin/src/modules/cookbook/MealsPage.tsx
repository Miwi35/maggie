import { useCallback, useEffect, useMemo, useState } from 'react'
import { useDataProvider, useNotify, Title } from 'react-admin'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import IconButton from '@mui/material/IconButton'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Paper from '@mui/material/Paper'
import Select from '@mui/material/Select'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import DeleteIcon from '@mui/icons-material/Delete'
import ShoppingBasketIcon from '@mui/icons-material/ShoppingBasket'
import { useMercure } from '../../hooks/useMercure'
import { GuestsStepper } from './GuestsStepper'
import { MealCard, mealTitle } from './MealCard'
import type { MealActions } from './MealCard'
import { MealGroceryChoice } from './MealGroceryChoice'
import { idOf } from './recipeSheet'
import {
  DAYS,
  FALLBACK_GUESTS,
  MEAL_SETTINGS_PATH,
  MEAL_TOPICS,
  SLOTS,
  addDays,
  byDayAndSlot,
  createdMealIri,
  distinctProducts,
  formatDate,
  getMonday,
  iriOf,
  mealRecipesOf,
  mealToChoose,
  slotLabel,
  weekLabel,
} from './mealsWeek'
import type { Meal, Recipe } from './mealsWeek'

type View = 'menu' | 'list'

const authHeaders = (): Record<string, string> => {
  const token = localStorage.getItem('token')

  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** Sends part of a meal: the answer, or a throw when the API refuses it. */
async function patchMeal(meal: Meal, body: Record<string, unknown>): Promise<Partial<Meal> | null> {
  const response = await fetch(iriOf('meals', meal), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json', ...authHeaders() },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw new Error(`PATCH answered ${response.status}`)

  return response.json().catch(() => null)
}

function Pill({ label, active = false, onClick }: { label: string; active?: boolean; onClick: () => void }) {
  return (
    <Chip
      label={label}
      clickable
      aria-pressed={active}
      color={active ? 'primary' : 'default'}
      variant={active ? 'filled' : 'outlined'}
      onClick={onClick}
    />
  )
}

function MealRow({ meal, known, busy, actions }: { meal: Meal; known: Map<string, Recipe>; busy: boolean; actions: MealActions }) {
  const title = mealTitle(meal)
  const date = new Date(`${meal.date}T12:00:00`)
  const dayName = DAYS[(date.getDay() + 6) % 7]
  const names = mealRecipesOf(meal, known)
    .map((entry) => entry.recipe?.name)
    .filter(Boolean)

  return (
    <TableRow data-testid={`meal-row-${meal.id}`}>
      <TableCell sx={{ whiteSpace: 'nowrap' }}>
        {dayName} {date.getDate()} {date.toLocaleDateString('fr-FR', { month: 'short' })}
      </TableCell>
      <TableCell>{slotLabel(meal.slot)}</TableCell>
      <TableCell>{names.length > 0 ? names.join(', ') : meal.summary}</TableCell>
      <TableCell>
        <GuestsStepper
          label="Convives"
          name={`Convives du repas ${title}`}
          hideLabel
          value={meal.guests ?? FALLBACK_GUESTS}
          isDefault={meal.guestsIsDefault === true}
          disabled={busy}
          onChange={(value) => actions.onGuests(meal, value)}
        />
      </TableCell>
      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
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
      </TableCell>
    </TableRow>
  )
}

export const MealsPage = () => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const [view, setView] = useState<View>('menu')
  const [weekStart, setWeekStart] = useState(() => getMonday(new Date()))
  const [meals, setMeals] = useState<Meal[]>([])
  const [recipes, setRecipes] = useState<Recipe[]>([])
  // The meals being written to, so a second tap waits for the first.
  const [busy, setBusy] = useState<Set<string>>(new Set())
  const [dialogOpen, setDialogOpen] = useState(false)
  const [dialogDate, setDialogDate] = useState('')
  const [dialogSlot, setDialogSlot] = useState('lunch')
  const [selectedRecipes, setSelectedRecipes] = useState<Recipe[]>([])
  // The guests of the meal being created: null while the user has not touched the field, so the meal keeps following the default.
  const [dialogGuests, setDialogGuests] = useState<number | null>(null)
  const [defaultGuests, setDefaultGuests] = useState(FALLBACK_GUESTS)
  // The meal whose ingredients are being chosen: the one just created, or one reopened.
  const [choiceMealIri, setChoiceMealIri] = useState<string | null>(null)

  const fetchMeals = useCallback(async () => {
    try {
      const { data } = await dataProvider.getList('meals', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'date', order: 'ASC' },
        // Days, both ends included: the week is Monday to Sunday.
        filter: {
          'date[after]': formatDate(weekStart),
          'date[before]': formatDate(addDays(weekStart, 6)),
        },
      })
      setMeals(data as Meal[])
    } catch {
      notify('Erreur lors du chargement des repas', { type: 'error' })
    }
  }, [dataProvider, weekStart, notify])

  useEffect(() => {
    fetchMeals()
  }, [fetchMeals])

  useMercure(MEAL_TOPICS, fetchMeals)

  useEffect(() => {
    dataProvider
      .getList('recipes', { pagination: { page: 1, perPage: 200 }, sort: { field: 'name', order: 'ASC' }, filter: {} })
      .then(({ data }) => setRecipes(data as Recipe[]))
      .catch(() => notify('Erreur lors du chargement des recettes', { type: 'error' }))
  }, [dataProvider, notify])

  const known = useMemo(() => new Map(recipes.map((recipe) => [idOf(recipe['@id'] ?? recipe.id), recipe])), [recipes])
  const sorted = useMemo(() => [...meals].sort(byDayAndSlot), [meals])
  const products = useMemo(() => distinctProducts(meals, known), [meals, known])

  const fetchDefaultGuests = async () => {
    try {
      const response = await fetch(MEAL_SETTINGS_PATH, { headers: { Accept: 'application/json', ...authHeaders() } })
      if (!response.ok) return
      const settings = await response.json()
      if (Number.isInteger(settings?.defaultGuests) && settings.defaultGuests >= 1) setDefaultGuests(settings.defaultGuests)
    } catch {
      // Without the default, the field starts from what the API would use.
    }
  }

  const openCreateDialog = (date: Date) => {
    const day = formatDate(date)
    const taken = new Set(meals.filter((meal) => meal.date === day).map((meal) => meal.slot))
    setDialogDate(day)
    setDialogSlot(SLOTS.find((slot) => !taken.has(slot.value))?.value ?? SLOTS[0].value)
    setSelectedRecipes([])
    setDialogGuests(null)
    fetchDefaultGuests()
    setChoiceMealIri(null)
    setDialogOpen(true)
  }

  const handleCreate = async () => {
    if (selectedRecipes.length === 0) {
      notify('Choisissez au moins une recette pour ce repas', { type: 'error' })
      return
    }

    try {
      const { data: created } = await dataProvider.create('meals', {
        data: {
          // The day and the slot, and nothing that looks like a time: the API
          // derives the instants the agenda shows (MAG-251). No agenda either:
          // the API files a meal in the « Repas » module agenda (MAG-324), and
          // titles it with its recipes' names (MAG-378).
          date: dialogDate,
          slot: dialogSlot,
          recipes: selectedRecipes.map((recipe) => iriOf('recipes', recipe)),
          // Absent while the field is untouched: the meal follows the user's default.
          ...(dialogGuests !== null && { guests: dialogGuests }),
        },
      })
      fetchMeals()
      notify('Repas créé', { type: 'success' })
      const iri = createdMealIri(created ?? {})
      if (iri) {
        setChoiceMealIri(iri)
      } else {
        setDialogOpen(false)
      }
    } catch {
      notify('Erreur lors de la création', { type: 'error' })
    }
  }

  const openChoice = useCallback((meal: Meal) => {
    setChoiceMealIri(iriOf('meals', meal))
    setDialogOpen(true)
  }, [])

  const closeDialog = useCallback(() => {
    setDialogOpen(false)
    setChoiceMealIri(null)
  }, [])

  const writing = async (meal: Meal, write: () => Promise<void>) => {
    setBusy((current) => new Set(current).add(meal.id))
    try {
      await write()
    } finally {
      setBusy((current) => {
        const next = new Set(current)
        next.delete(meal.id)
        return next
      })
    }
  }

  const patchLocal = (meal: Meal, changes: Partial<Meal>) =>
    setMeals((current) => current.map((m) => (m.id === meal.id ? { ...m, ...changes } : m)))

  const actions: MealActions = {
    // `guests: null` gives the meal back to the default.
    onGuests: (meal, guests) =>
      writing(meal, async () => {
        try {
          const saved = await patchMeal(meal, { guests })
          patchLocal(meal, {
            guests: saved?.guests ?? guests ?? defaultGuests,
            guestsIsDefault: saved?.guestsIsDefault ?? guests === null,
          })
        } catch {
          notify('Le nombre de convives n’a pas pu être enregistré', { type: 'error' })
        }
      }),
    onRecipes: (meal, iris) =>
      writing(meal, async () => {
        try {
          const saved = await patchMeal(meal, { recipes: iris })
          patchLocal(meal, { recipes: saved?.recipes ?? iris, summary: saved?.summary ?? meal.summary })
        } catch {
          notify('Les recettes du repas n’ont pas pu être enregistrées', { type: 'error' })
        }
      }),
    onDelete: (meal) =>
      writing(meal, async () => {
        try {
          await dataProvider.delete('meals', { id: meal.id, previousData: meal })
          setMeals((current) => current.filter((m) => m.id !== meal.id))
          notify('Repas supprimé', { type: 'success' })
        } catch {
          notify('Erreur lors de la suppression', { type: 'error' })
        }
      }),
    onReview: openChoice,
  }

  const today = new Date()
  const todayDay = formatDate(today)
  const days = DAYS.map((name, index) => ({ name, date: addDays(weekStart, index) }))
  const choice = mealToChoose(meals)

  const mealCard = (meal: Meal) => <MealCard key={meal.id} meal={meal} recipes={recipes} known={known} busy={busy.has(meal.id)} actions={actions} />

  return (
    <Box sx={{ p: { xs: 1, md: 2 }, display: 'flex', flexDirection: 'column', gap: 2 }}>
      <Title title="Repas" />

      <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1 }}>
        <IconButton aria-label="Semaine précédente" onClick={() => setWeekStart(addDays(weekStart, -7))}>
          <ChevronLeftIcon />
        </IconButton>
        <Typography component="span" variant="h6" sx={{ minWidth: '9ch', textAlign: 'center', fontSize: { xs: '1rem', md: '1.25rem' } }}>
          {weekLabel(weekStart, today)}
        </Typography>
        <IconButton aria-label="Semaine suivante" onClick={() => setWeekStart(addDays(weekStart, 7))}>
          <ChevronRightIcon />
        </IconButton>
        <Pill label="Aujourd'hui" onClick={() => setWeekStart(getMonday(new Date()))} />
        <Box sx={{ flex: 1 }} />
        <Pill label="Menu" active={view === 'menu'} onClick={() => setView('menu')} />
        <Pill label="Liste" active={view === 'list'} onClick={() => setView('list')} />
      </Box>

      {meals.length > 0 && (
        <Paper data-testid="week-ingredients" variant="outlined" sx={{ px: 2, py: 1, display: 'flex', alignItems: 'center', flexWrap: 'wrap', columnGap: 1.5 }}>
          <Typography sx={{ fontWeight: 500 }}>Ingrédients de la semaine</Typography>
          <Typography color="text.secondary">·</Typography>
          <Typography>{meals.length} repas</Typography>
          <Typography color="text.secondary">·</Typography>
          <Typography sx={{ flex: 1 }}>
            {products} produit{products > 1 ? 's' : ''}
          </Typography>
          <Button disabled={!choice} onClick={() => choice && openChoice(choice)}>
            Choisir
          </Button>
        </Paper>
      )}

      {view === 'menu' ? (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {days.map(({ name, date }, index) => {
            const day = formatDate(date)
            const isToday = day === todayDay

            return (
              <Box
                key={name}
                component="section"
                data-testid={`meal-day-${index}`}
                aria-current={isToday ? 'date' : undefined}
                sx={{ pl: 1.5, borderLeft: 3, borderColor: isToday ? 'primary.main' : 'transparent' }}
              >
                <Typography
                  variant="subtitle1"
                  component="h2"
                  sx={{ mb: 1, fontWeight: 600, color: isToday ? 'primary.main' : 'text.primary' }}
                >
                  {name} {date.getDate()}
                </Typography>
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 1.5, alignItems: 'start' }}>
                  {sorted.filter((meal) => meal.date === day).map(mealCard)}
                </Box>
                <Button size="small" sx={{ mt: 0.5 }} onClick={() => openCreateDialog(date)}>
                  + Ajouter un repas
                </Button>
              </Box>
            )
          })}
        </Box>
      ) : (
        <>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Jour</TableCell>
                  <TableCell>Créneau</TableCell>
                  <TableCell>Recettes</TableCell>
                  <TableCell>Convives</TableCell>
                  <TableCell />
                </TableRow>
              </TableHead>
              <TableBody>
                {sorted.map((meal) => (
                  <MealRow key={meal.id} meal={meal} known={known} busy={busy.has(meal.id)} actions={actions} />
                ))}
                {sorted.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={5}>Aucun repas cette semaine</TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <Button
            size="small"
            sx={{ alignSelf: 'flex-start' }}
            onClick={() => openCreateDialog(days.find(({ date }) => formatDate(date) === todayDay)?.date ?? weekStart)}
          >
            + Ajouter un repas
          </Button>
        </>
      )}

      {/* The meal, then — once it exists — which of its ingredients go on the list. The same second step is reopened from a planned meal. */}
      <Dialog open={dialogOpen} onClose={closeDialog} maxWidth="sm" fullWidth>
        {choiceMealIri ? (
          <>
            <DialogTitle>Courses du repas</DialogTitle>
            <MealGroceryChoice mealIri={choiceMealIri} onDone={closeDialog} />
          </>
        ) : (
          <>
            <DialogTitle>Ajouter un repas</DialogTitle>
            <DialogContent>
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
                <TextField label="Date" type="date" value={dialogDate} onChange={(e) => setDialogDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
                <FormControl>
                  <InputLabel id="meal-slot-label">Créneau</InputLabel>
                  <Select labelId="meal-slot-label" value={dialogSlot} onChange={(e) => setDialogSlot(e.target.value)} label="Créneau">
                    {SLOTS.map((s) => (
                      <MenuItem key={s.value} value={s.value}>
                        {s.label}
                      </MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <GuestsStepper label="Convives" value={dialogGuests ?? defaultGuests} isDefault={dialogGuests === null} onChange={setDialogGuests} />
                <Autocomplete
                  multiple
                  options={recipes}
                  getOptionLabel={(option) => option.name}
                  value={selectedRecipes}
                  onChange={(_, value) => setSelectedRecipes(value)}
                  renderInput={(params) => <TextField {...params} label="Menu" placeholder="Chercher..." />}
                  isOptionEqualToValue={(option, value) => option.id === value.id}
                />
              </Box>
            </DialogContent>
            <DialogActions>
              <Button onClick={closeDialog}>Annuler</Button>
              <Button onClick={handleCreate} variant="contained">
                Créer
              </Button>
            </DialogActions>
          </>
        )}
      </Dialog>
    </Box>
  )
}
