import { useState, useEffect, useCallback } from 'react'
import { useDataProvider, useNotify, Title } from 'react-admin'
import Box from '@mui/material/Box'
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

function formatDate(d: Date): string {
  return d.toISOString().split('T')[0]
}

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

interface Meal {
  id: string
  '@id': string
  startAt: string
  slot: string
  summary: string
  recipes: Recipe[]
}

export const MealsWeekView = () => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
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
      const weekEnd = addDays(weekStart, 6)
      const { data } = await dataProvider.getList('meals', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'startAt', order: 'ASC' },
        filter: {
          'startAt[after]': formatDate(weekStart),
          'startAt[before]': formatDate(addDays(weekEnd, 1)),
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
  useEffect(() => {
    const hubUrl = new URL('/.well-known/mercure', window.location.origin)
    hubUrl.searchParams.append('topic', '/api/meals/{id}')
    const es = new EventSource(hubUrl.toString())
    es.onmessage = () => fetchMeals()
    return () => es.close()
  }, [fetchMeals])

  const fetchRecipes = async () => {
    try {
      const { data } = await dataProvider.getList('recipes', {
        pagination: { page: 1, perPage: 100 },
        sort: { field: 'name', order: 'ASC' },
        filter: {},
      })
      setRecipes(data as Recipe[])
    } catch {
      // ignore
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

  const handleCreate = async () => {
    try {
      const recipeIris = selectedRecipes.map((r) => r['@id'] || `/api/recipes/${r.id}`)
      await dataProvider.create('meals', {
        data: {
          startAt: `${dialogDate}T00:00:00+01:00`,
          endAt: `${dialogDate}T23:59:59+01:00`,
          slot: dialogSlot,
          allDay: true,
          summary:
            (dialogSlot === 'lunch' ? 'Déjeuner' : 'Dîner') +
            (selectedRecipes.length > 0 ? ' : ' + selectedRecipes.map((r) => r.name).join(', ') : ''),
          recipes: recipeIris,
          agenda: '/api/agendas', // Will be auto-assigned by handler
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

  const getMealsForCell = (dayIndex: number, slot: string): Meal[] => {
    const date = formatDate(addDays(weekStart, dayIndex))
    return meals.filter((m) => m.startAt.startsWith(date) && m.slot === slot)
  }

  const weekEnd = addDays(weekStart, 6)
  const weekLabel = `${weekStart.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long' })} - ${weekEnd.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })}`

  return (
    <Box sx={{ p: 2 }}>
      <Title title="Repas de la semaine" />

      {/* Week navigation */}
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', mb: 3 }}>
        <IconButton onClick={() => setWeekStart(addDays(weekStart, -7))}>
          <ChevronLeftIcon />
        </IconButton>
        <Typography variant="h5" sx={{ mx: 3, minWidth: 300, textAlign: 'center' }}>
          {weekLabel}
        </Typography>
        <IconButton onClick={() => setWeekStart(addDays(weekStart, 7))}>
          <ChevronRightIcon />
        </IconButton>
        <Button variant="outlined" size="small" sx={{ ml: 2 }} onClick={() => setWeekStart(getMonday(new Date()))}>
          Aujourd'hui
        </Button>
      </Box>

      {/* Week grid */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: '100px repeat(7, 1fr)',
          gap: 0.5,
          opacity: loading ? 0.5 : 1,
        }}
      >
        {/* Header row */}
        <Box />
        {DAYS.map((day, i) => {
          const date = addDays(weekStart, i)
          const isToday = formatDate(date) === formatDate(new Date())
          return (
            <Paper
              key={day}
              elevation={0}
              sx={{
                p: 1,
                textAlign: 'center',
                bgcolor: isToday ? 'primary.main' : 'grey.100',
                color: isToday ? 'primary.contrastText' : 'text.primary',
                borderRadius: 1,
              }}
            >
              <Typography variant="subtitle2">{day}</Typography>
              <Typography variant="caption">{date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })}</Typography>
            </Paper>
          )
        })}

        {/* Meal rows */}
        {SLOTS.map(({ value: slot, label }) => (
          <>
            <Box key={`label-${slot}`} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Typography variant="body2" color="text.secondary" sx={{ fontWeight: 500 }}>
                {label}
              </Typography>
            </Box>
            {DAYS.map((_, dayIndex) => {
              const cellMeals = getMealsForCell(dayIndex, slot)
              return (
                <Paper
                  key={`${slot}-${dayIndex}`}
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
                    <Box key={meal.id} sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
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
            })}
          </>
        ))}
      </Box>

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
