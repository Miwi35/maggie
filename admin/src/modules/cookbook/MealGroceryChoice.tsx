import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNotify } from 'react-admin'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import CircularProgress from '@mui/material/CircularProgress'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import FormControlLabel from '@mui/material/FormControlLabel'
import Typography from '@mui/material/Typography'
import { StockStateChip } from '../grocery/StockStateChip'
import { neededLabel } from '../grocery/groceryQuantity'
import type { NeededEntry } from '../grocery/groceryQuantity'
import { quantityLabel } from '../grocery/packaging'

interface PreviewLine {
  ingredientId: string
  name: string
  quantity: number
  unit: string
  packaging: { unit: string; size: number | null; sizeUnit: string | null } | null
  toBuy: { quantity: number; unit: string }
  stockState: string
  suggested: boolean
  onList?: { needed: NeededEntry[]; packages: number | null } | null
  otherMeals?: OtherMeal[]
  withThisMeal?: { needed: NeededEntry[]; packagesTotal: number | null; packagesToAdd: number | null } | null
}

interface OtherMeal {
  mealId: string
  name: string
  date: string
  quantity: number
  unit: string | null
}

interface Preview {
  /** What the quantities are scaled to (MAG-482), and whether that is the user's default. */
  guests?: number
  guestsIsDefault?: boolean
  ingredients: PreviewLine[]
}

/** One row per product: a product used in two units is one choice (the API takes an ingredient id). */
interface Row {
  ingredientId: string
  name: string
  lines: PreviewLine[]
  stockState: string
  suggested: boolean
  onList: PreviewLine['onList']
  otherMeals: OtherMeal[]
  withThisMeal: PreviewLine['withThisMeal']
}

function rowsOf(lines: PreviewLine[]): Row[] {
  const rows = new Map<string, Row>()

  for (const line of lines) {
    const row = rows.get(line.ingredientId)
    if (row) {
      row.lines.push(line)
    } else {
      rows.set(line.ingredientId, {
        ingredientId: line.ingredientId,
        name: line.name,
        lines: [line],
        stockState: line.stockState,
        suggested: line.suggested,
        onList: line.onList ?? null,
        otherMeals: line.otherMeals ?? [],
        withThisMeal: line.withThisMeal ?? null,
      })
    }
  }

  return [...rows.values()]
}

/** « 1 paquet (500 g) », or the recipe's own quantity for a product bought loose. */
function toBuyLabel(row: Row): string {
  return row.lines
    .map(({ toBuy, packaging }) => {
      const bought = quantityLabel(toBuy.quantity, toBuy.unit)
      const content = packaging?.size != null && packaging.sizeUnit ? ` (${quantityLabel(packaging.size, packaging.sizeUnit)})` : ''

      return bought + content
    })
    .join(' + ')
}

const WEEKDAYS = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.']

/** « jeu. » — the short weekday of a `YYYY-MM-DD` date, read as a calendar day (no timezone shift). */
function weekdayOf(date: string): string {
  const [year, month, day] = date.split('-').map(Number)

  return WEEKDAYS[new Date(Date.UTC(year, month - 1, day)).getUTCDay()] ?? ''
}

/** « déjà prévu : 300 g (Bolognaise, jeu.) » — what the list already holds from other meals, null when nothing. */
function alreadyPlannedLabel(row: Row): string | null {
  const needed = neededLabel(row.onList?.needed)
  if (!needed && row.otherMeals.length === 0) return null
  const meals = row.otherMeals.map((meal) => `${meal.name}, ${weekdayOf(meal.date)}`).join(', ')

  return `déjà prévu : ${[needed, meals && `(${meals})`].filter(Boolean).join(' ')}`
}

/** « 2 paquets au total (+1) » — the line once this meal is added; null for a product without packaging. */
function packagesTotalLabel(row: Row): string | null {
  const total = row.withThisMeal?.packagesTotal
  if (total == null) return null
  const toAdd = row.withThisMeal?.packagesToAdd

  return `${quantityLabel(total, row.lines.find((line) => line.packaging)?.packaging?.unit ?? 'pack')} au total${toAdd ? ` (+${toAdd})` : ''}`
}

const guestsLabel = (count: number): string => `${count} convive${count > 1 ? 's' : ''}`

const recipeLabel = (row: Row): string => row.lines.map(({ quantity, unit }) => quantityLabel(quantity, unit)).join(' + ')

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')

  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** The server answered, with a status that is not a success: no point asking again. */
class PreviewRefused extends Error {}

/** A request that never got an answer (connection reset, request dropped by the browser) is asked once more; an answer, good or bad, is final. */
async function fetchPreview(mealIri: string, signal: AbortSignal): Promise<Preview> {
  for (let attempt = 1; ; attempt++) {
    try {
      const response = await fetch(`${mealIri}/grocery_preview`, { headers: { Accept: 'application/json', ...authHeaders() }, signal })
      if (!response.ok) throw new PreviewRefused(`grocery_preview answered ${response.status}`)

      return (await response.json()) as Preview
    } catch (error) {
      if (signal.aborted || error instanceof PreviewRefused || attempt >= 2) throw error
    }
  }
}

async function refusalOf(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { error?: unknown }

    return typeof body.error === 'string' ? body.error : ''
  } catch {
    return ''
  }
}

/**
 * The second step of « Ajouter un repas » (MAG-296): the meal exists, the owner
 * ticks which of its ingredients go on the grocery list.
 *
 * Ticked from the start: only what the stock asks for (`suggested`) — ticking
 * everything would put back the list the project corrects. « Plus tard » closes
 * without adding; the meal stays.
 */
export const MealGroceryChoice = ({ mealIri, onDone }: { mealIri: string; onDone: () => void }) => {
  const notify = useNotify()
  const [rows, setRows] = useState<Row[] | null>(null)
  const [guests, setGuests] = useState<{ count: number; isDefault: boolean } | null>(null)
  const [ticked, setTicked] = useState<Set<string>>(new Set())
  const [loadFailed, setLoadFailed] = useState(false)
  const [sending, setSending] = useState(false)
  const [notAdded, setNotAdded] = useState<string | null>(null)

  const mounted = useRef(true)
  const loading = useRef<AbortController | null>(null)

  useEffect(() => {
    mounted.current = true

    return () => {
      mounted.current = false
    }
  }, [])

  const load = useCallback(async () => {
    loading.current?.abort()
    const controller = new AbortController()
    loading.current = controller
    setLoadFailed(false)
    try {
      const preview = await fetchPreview(mealIri, controller.signal)
      const loaded = rowsOf(preview.ingredients)
      // Aborted — unmounted, or replaced by a newer load — is not a failure: say nothing.
      if (controller.signal.aborted) return

      setGuests(typeof preview.guests === 'number' ? { count: preview.guests, isDefault: preview.guestsIsDefault === true } : null)

      if (loaded.length === 0) {
        onDone()
        return
      }
      setRows(loaded)
      setTicked(new Set(loaded.filter((row) => row.suggested).map((row) => row.ingredientId)))
    } catch {
      if (!controller.signal.aborted) setLoadFailed(true)
    }
  }, [mealIri, onDone])

  useEffect(() => {
    load()

    return () => loading.current?.abort()
  }, [load])

  const toggle = (ingredientId: string) =>
    setTicked((current) => {
      const next = new Set(current)
      if (!next.delete(ingredientId)) next.add(ingredientId)

      return next
    })

  const tickedRows = useMemo(() => (rows ?? []).filter((row) => ticked.has(row.ingredientId)), [rows, ticked])

  const add = async () => {
    setSending(true)
    setNotAdded(null)
    try {
      const response = await fetch(`${mealIri}/grocery_items`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...authHeaders() },
        body: JSON.stringify({ ingredients: tickedRows.map((row) => ({ ingredientId: row.ingredientId })) }),
      })
      if (!response.ok) {
        const reason = await refusalOf(response)
        throw new Error(reason)
      }
      notify('Ingrédients ajoutés à la liste de courses', { type: 'success' })
      if (mounted.current) onDone()
    } catch (error) {
      const reason = error instanceof Error && error.message ? ` (${error.message})` : ''
      const what = tickedRows.length > 0 ? tickedRows.map((row) => row.name).join(', ') : 'les ingrédients cochés'
      setNotAdded(`Non ajouté à la liste de courses : ${what}${reason}. Le repas est bien créé ; votre sélection est conservée, vous pouvez réessayer.`)
    } finally {
      setSending(false)
    }
  }

  if (loadFailed) {
    return (
      <>
        <DialogContent>
          <Alert severity="error">La liste des ingrédients du repas n’a pas pu être chargée.</Alert>
        </DialogContent>
        <DialogActions>
          <Button onClick={onDone}>Plus tard</Button>
          <Button onClick={load} variant="contained">
            Réessayer
          </Button>
        </DialogActions>
      </>
    )
  }

  if (rows === null) {
    return (
      <DialogContent sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
        <CircularProgress aria-label="Chargement des ingrédients" />
      </DialogContent>
    )
  }

  return (
    <>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Quels ingrédients ajouter à la liste de courses ?
        </Typography>
        {guests && (
          <Typography variant="body2" sx={{ mb: 1 }} data-testid="meal-guests">
            Quantités pour {guestsLabel(guests.count)}
            {guests.isDefault ? ' (par défaut)' : ''}
          </Typography>
        )}
        <Box sx={{ display: 'flex', gap: 1, mb: 1 }}>
          <Button size="small" onClick={() => setTicked(new Set(rows.map((row) => row.ingredientId)))}>
            Tout cocher
          </Button>
          <Button size="small" onClick={() => setTicked(new Set())}>
            Tout décocher
          </Button>
        </Box>
        <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
          {rows.map((row) => (
            <Box component="li" key={row.ingredientId} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <FormControlLabel
                sx={{ flex: 1, mr: 0 }}
                control={<Checkbox checked={ticked.has(row.ingredientId)} onChange={() => toggle(row.ingredientId)} />}
                label={row.name}
              />
              <Box sx={{ textAlign: 'right' }}>
                <Typography variant="body2">{toBuyLabel(row)}</Typography>
                <Typography variant="caption" color="text.secondary">
                  Recette{guests ? ` pour ${guestsLabel(guests.count)}` : ''} : {recipeLabel(row)}
                </Typography>
                {alreadyPlannedLabel(row) && (
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }} data-testid="already-planned">
                    {alreadyPlannedLabel(row)}
                  </Typography>
                )}
                {packagesTotalLabel(row) && (
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }} data-testid="packages-total">
                    {packagesTotalLabel(row)}
                  </Typography>
                )}
              </Box>
              {row.stockState !== 'in_stock' && <StockStateChip state={row.stockState} />}
            </Box>
          ))}
        </Box>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
          {tickedRows.length} sur {rows.length} cochés
        </Typography>
        {notAdded && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {notAdded}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onDone} disabled={sending}>
          Plus tard
        </Button>
        <Button onClick={add} variant="contained" disabled={sending}>
          Ajouter aux courses
        </Button>
      </DialogActions>
    </>
  )
}
