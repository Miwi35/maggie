import { useEffect, useState } from 'react'
import { useRecordContext } from 'react-admin'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import { quantityLabel } from '../grocery/packaging'
import { GuestsStepper } from './GuestsStepper'

interface ScaledLine {
  id?: string
  ingredientName?: string
  quantity: number
  unit: string
}

type Simulation = { state: 'loading' } | { state: 'failed' } | { state: 'ready'; lines: ScaledLine[] }

/**
 * « Pour N personnes » on the recipe sheet (MAG-483): the quantities of the
 * recipe for another number of people, as the API scales them
 * (`GET /api/recipes/{id}?guests=N`) — the same rule, the same rounding as the
 * grocery list. The client computes nothing and nothing is saved: going back to
 * the recipe's own servings is one click away.
 */
export const RecipeScaleSimulation = () => {
  const record = useRecordContext<{ id: string; servings?: number }>()
  const servings = record?.servings ?? 1
  const [guests, setGuests] = useState<number | null>(null)
  const [simulation, setSimulation] = useState<Simulation | null>(null)

  const recipeId = record ? String(record.id).split('/').pop() : null
  const shown = guests ?? servings
  const simulating = recipeId !== null && shown !== servings

  useEffect(() => {
    if (!simulating) {
      setSimulation(null)
      return
    }

    const controller = new AbortController()
    setSimulation({ state: 'loading' })
    const token = localStorage.getItem('token')
    fetch(`/api/recipes/${recipeId}?guests=${shown}`, {
      signal: controller.signal,
      headers: { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
      .then(async (response) => {
        if (!response.ok) throw new Error(`GET answered ${response.status}`)
        const body = await response.json()
        if (controller.signal.aborted) return
        setSimulation({ state: 'ready', lines: Array.isArray(body.ingredients) ? body.ingredients : [] })
      })
      .catch(() => {
        if (!controller.signal.aborted) setSimulation({ state: 'failed' })
      })

    return () => controller.abort()
  }, [simulating, recipeId, shown])

  if (!record) return null

  return (
    <Box sx={{ width: '100%', mb: 2 }} data-testid="recipe-scale-simulation">
      <Typography variant="subtitle2" gutterBottom>
        Pour {shown} personne{shown > 1 ? 's' : ''}
      </Typography>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <GuestsStepper label="Simuler pour" value={shown} onChange={setGuests} />
        {simulating && (
          <Button size="small" onClick={() => setGuests(null)}>
            Parts de la recette ({servings})
          </Button>
        )}
      </Box>

      {!simulating && (
        <Typography variant="caption" color="text.secondary">
          Les quantités sont celles de la recette, pour {servings} personne{servings > 1 ? 's' : ''}. Rien n'est enregistré.
        </Typography>
      )}
      {simulation?.state === 'loading' && <CircularProgress size={20} sx={{ mt: 1 }} />}
      {simulation?.state === 'failed' && (
        <Alert severity="error" sx={{ mt: 1 }}>
          Impossible de simuler les quantités pour {shown} personnes : celles de la recette restent valables.
        </Alert>
      )}
      {simulation?.state === 'ready' && (
        <Box component="ul" sx={{ mt: 1, mb: 0, pl: 3 }} aria-label={`Quantités pour ${shown} personnes`}>
          {simulation.lines.map((line, index) => (
            <li key={line.id ?? index}>
              {quantityLabel(line.quantity, line.unit)} {line.ingredientName}
            </li>
          ))}
        </Box>
      )}
    </Box>
  )
}
