import { Fragment, useState, useEffect, useCallback, useMemo } from 'react'
import { useDataProvider, useNotify, Title } from 'react-admin'
import Box from '@mui/material/Box'
import { localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { useItemTransitions, transitionSx } from '../../hooks/useItemTransitions'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogTitle from '@mui/material/DialogTitle'
import DialogContent from '@mui/material/DialogContent'
import DialogActions from '@mui/material/DialogActions'
import TextField from '@mui/material/TextField'
import Autocomplete from '@mui/material/Autocomplete'
import Select from '@mui/material/Select'
import MenuItem from '@mui/material/MenuItem'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import RestaurantIcon from '@mui/icons-material/Restaurant'

const MEAL_TOPICS = ['/api/meals/{id}']
const DAYS = ['Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi', 'Dimanche']
const SLOTS = [
  { value: 'lunch', label: 'Déjeuner' },
  { value: 'dinner', label: 'Dîner' },
]

function getMonday(d: Date): Date {
  const date = new Date(d)
  const day = date.getDay()
  const diff = date.getDate() - day + (day === 0 ? -6 : 1)
  date.setDate(diff)
  date.setHours(0, 0, 0, 0)
  return date
}

const formatDate = localDay

function addDays(d: Date, days: number): Date {
  const result = new Date(d)
  result.setDate(result.getDate() + days)
  return result
}

interface Recipe {
  id: string
  '@id'?: string
  name: string
}

interface Agenda {
  id: string
  '@id'?: string
  name: string
  default?: boolean
}

interface Meal {
  id: string
  '@id': string
  /** The day, `YYYY-MM-DD`. A meal has no time (MAG-251). */
  date: string
  slot: string
  summary: string
  recipes: Recipe[]
}

export const MealsWeekView = () => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const isNarrow = useNarrowScreen()
  const [weekStart, setWeekStart] = useState(() => getMonday(new Date()))
  const [meals, setMeals] = useState<Meal[]>([])
  const [loading, setLoading] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [dialogDate, setDialogDate] = useState('')
  const [dialogSlot, setDialogSlot] = useState('lunch')
  const [recipes, setRecipes] = useState<Recipe[]>([])
  const [selectedRecipes, setSelectedRecipes] = useState<Recipe[]>([])

  const fetchMeals = useCallback(async () => {
    setLoading(true)
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
    } finally {
      setLoading(false)
    }
  }, [dataProvider, weekStart, notify])

  useEffect(() => {
    fetchMeals()
  }, [fetchMeals])

  // Mercure subscription
  useMercure(MEAL_TOPICS, fetchMeals)

  // Track meal additions/removals for animations
  const { addedIds, removingItems } = useItemTransitions(
    meals,
    useCallback((m: Meal) => m.id, []),
  )
  const removingIds = useMemo(() => new Set(removingItems.map((m) => m.id)), [removingItems])

  const fetchRecipes = async () => {
    try {
      const { data } = await dataProvider.getList('recipes', {
        pagination: { page: 1, perPage: 100 },
        sort: { field: 'name', order: 'ASC' },
        filter: {},
      })
      setRecipes(data as Recipe[])
    } catch {
      notify('Erreur lors du chargement des recettes', { type: 'error' })
    }
  }

  const openCreateDialog = (dayIndex: number, slot: string) => {
    const date = addDays(weekStart, dayIndex)
    setDialogDate(formatDate(date))
    setDialogSlot(slot)
    setSelectedRecipes([])
    fetchRecipes()
    setDialogOpen(true)
  }

  const findMealAgendaIri = async (): Promise<string | null> => {
    const { data } = await dataProvider.getList('agendas', {
      pagination: { page: 1, perPage: 50 },
      sort: { field: 'name', order: 'ASC' },
      filter: {},
    })
    const agendas = data as Agenda[]
    const agenda = agendas.find((a) => a.name === 'Repas') ?? agendas.find((a) => a.default) ?? agendas[0]
    return agenda ? agenda['@id'] || `/api/agendas/${agenda.id}` : null
  }

  const handleCreate = async () => {
    if (selectedRecipes.length === 0) {
      notify('Choisissez au moins une recette pour ce repas', { type: 'error' })
      return
    }

    try {
      const agendaIri = await findMealAgendaIri()
      if (!agendaIri) {
        notify('Aucun agenda disponible pour accueillir le repas', { type: 'error' })
        return
      }
      const recipeIris = selectedRecipes.map((r) => r['@id'] || `/api/recipes/${r.id}`)
      await dataProvider.create('meals', {
        data: {
          // The day and the slot, and nothing that looks like a time: the API
          // derives the instants the agenda shows (MAG-251).
          date: dialogDate,
          slot: dialogSlot,
          summary:
            (dialogSlot === 'lunch' ? 'Déjeuner' : 'Dîner') +
            ' : ' +
            selectedRecipes.map((r) => r.name).join(', '),
          recipes: recipeIris,
          agenda: agendaIri,
        },
      })
      setDialogOpen(false)
      fetchMeals()
      notify('Repas créé', { type: 'success' })
    } catch {
      notify('Erreur lors de la création', { type: 'error' })
    }
  }

  const handleDelete = async (meal: Meal) => {
    try {
      await dataProvider.delete('meals', { id: meal.id, previousData: meal })
      fetchMeals()
      notify('Repas supprimé', { type: 'success' })
    } catch {
      notify('Erreur lors de la suppression', { type: 'error' })
    }
  }

  const getMealsForCell = useCallback(
    (dayIndex: number, slot: string): Meal[] => {
      const date = formatDate(addDays(weekStart, dayIndex))
      const currentIds = new Set(meals.map((m) => m.id))
      const ghosts = removingItems.filter((m) => m.date === date && m.slot === slot && !currentIds.has(m.id))
      return [...meals.filter((m) => m.date === date && m.slot === slot), ...ghosts]
    },
    [meals, weekStart, removingItems],
  )

  const weekEnd = addDays(weekStart, 6)
  const weekLabel = `${weekStart.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long' })} - ${weekEnd.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })}`

  const mealCell = (slot: string, dayIndex: number) => {
    const cellMeals = getMealsForCell(dayIndex, slot)

    return (
      <Paper
        data-testid={`meal-cell-${slot}-${dayIndex}`}
        variant="outlined"
        sx={{
          p: 1,
          minHeight: 80,
          display: 'flex',
          flexDirection: 'column',
          gap: 0.5,
          cursor: 'pointer',
          '&:hover': { bgcolor: 'action.hover' },
        }}
        onClick={() => cellMeals.length === 0 && openCreateDialog(dayIndex, slot)}
      >
        {cellMeals.map((meal) => (
          <Box key={meal.id} sx={{ display: 'flex', alignItems: 'center', gap: 0.5, borderRadius: 1, ...transitionSx(meal.id, addedIds, removingIds) }}>
            <RestaurantIcon sx={{ fontSize: 14, color: 'primary.main' }} />
            <Box sx={{ flex: 1, minWidth: 0 }}>
              {meal.recipes?.map((r: Recipe) => (
                <Chip key={r.id || r.name} label={r.name} size="small" sx={{ mr: 0.5, mb: 0.5 }} />
              ))}
              {(!meal.recipes || meal.recipes.length === 0) && (
                <Typography variant="caption" color="text.secondary">
                  {meal.summary}
                </Typography>
              )}
            </Box>
            <IconButton
              size="small"
              onClick={(e) => {
                e.stopPropagation()
                handleDelete(meal)
              }}
            >
              <DeleteIcon fontSize="small" />
            </IconButton>
          </Box>
        ))}
        {cellMeals.length === 0 && (
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', flex: 1, opacity: 0.3 }}>
            <AddIcon />
          </Box>
        )}
      </Paper>
    )
  }

  const dayHeader = (day: string, dayIndex: number) => {
    const date = addDays(weekStart, dayIndex)
    const isToday = formatDate(date) === formatDate(new Date())

    return (
      <Paper
        elevation={0}
        sx={{
          p: 1,
          textAlign: 'center',
          bgcolor: isToday ? 'primary.main' : 'action.selected',
          color: isToday ? 'primary.contrastText' : 'text.primary',
          borderRadius: 1,
        }}
      >
        <Typography variant="subtitle2">{day}</Typography>
        <Typography variant="caption">{date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })}</Typography>
      </Paper>
    )
  }

  return (
    <Box sx={{ p: { xs: 1, md: 2 } }}>
      <Title title="Repas de la semaine" />

      {/* Week navigation */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexWrap: 'wrap',
          gap: 1,
          mb: 3,
        }}
      >
        <IconButton onClick={() => setWeekStart(addDays(weekStart, -7))}>
          <ChevronLeftIcon />
        </IconButton>
        <Typography
          variant="h5"
          sx={{
            mx: { xs: 0, md: 3 },
            minWidth: { xs: 0, md: 300 },
            textAlign: 'center',
            fontSize: { xs: '1.1rem', md: '1.5rem' },
          }}
        >
          {weekLabel}
        </Typography>
        <IconButton onClick={() => setWeekStart(addDays(weekStart, 7))}>
          <ChevronRightIcon />
        </IconButton>
        <Button variant="outlined" size="small" sx={{ ml: { xs: 0, md: 2 } }} onClick={() => setWeekStart(getMonday(new Date()))}>
          Aujourd'hui
        </Button>
      </Box>

      {/* The week, one way or the other (MAG-38). Seven columns across 393px
          give each day 42px — a chip with a recipe name in it has nowhere to
          go. Below `md` the week reads downwards instead: one card per day,
          its two meals side by side. Same cells, same handles. */}
      {isNarrow ? (
        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            gap: 1.5,
            opacity: loading && meals.length === 0 ? 0.5 : 1,
          }}
        >
          {DAYS.map((day, dayIndex) => (
            <Box key={day}>
              {dayHeader(day, dayIndex)}
              <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 0.5, mt: 0.5 }}>
                {SLOTS.map(({ value: slot, label }) => (
                  <Box key={slot}>
                    <Typography variant="caption" color="text.secondary" sx={{ pl: 0.5, fontWeight: 500 }}>
                      {label}
                    </Typography>
                    {mealCell(slot, dayIndex)}
                  </Box>
                ))}
              </Box>
            </Box>
          ))}
        </Box>
      ) : (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: '100px repeat(7, 1fr)',
            gap: 0.5,
            opacity: loading && meals.length === 0 ? 0.5 : 1,
          }}
        >
          {/* Header row */}
          <Box />
          {DAYS.map((day, i) => (
            <Box key={day}>{dayHeader(day, i)}</Box>
          ))}

          {/* Meal rows */}
          {SLOTS.map(({ value: slot, label }) => (
            <Fragment key={slot}>
              <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <Typography variant="body2" color="text.secondary" sx={{ fontWeight: 500 }}>
                  {label}
                </Typography>
              </Box>
              {DAYS.map((_, dayIndex) => (
                <Fragment key={`${slot}-${dayIndex}`}>{mealCell(slot, dayIndex)}</Fragment>
              ))}
            </Fragment>
          ))}
        </Box>
      )}

      {/* Create meal dialog */}
      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Ajouter un repas</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
            <TextField label="Date" type="date" value={dialogDate} onChange={(e) => setDialogDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            <FormControl>
              <InputLabel>Créneau</InputLabel>
              <Select value={dialogSlot} onChange={(e) => setDialogSlot(e.target.value)} label="Créneau">
                {SLOTS.map((s) => (
                  <MenuItem key={s.value} value={s.value}>
                    {s.label}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <Autocomplete
              multiple
              options={recipes}
              getOptionLabel={(option) => option.name}
              value={selectedRecipes}
              onChange={(_, value) => setSelectedRecipes(value)}
              renderInput={(params) => <TextField {...params} label="Recettes" placeholder="Chercher..." />}
              isOptionEqualToValue={(option, value) => option.id === value.id}
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Annuler</Button>
          <Button onClick={handleCreate} variant="contained">
            Créer
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
