import { useCallback, useEffect, useMemo, useState } from 'react'
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
}

interface Preview {
  ingredients: PreviewLine[]
}

/** One row per product: a product used in two units is one choice (the API takes an ingredient id). */
interface Row {
  ingredientId: string
  name: string
  lines: PreviewLine[]
  stockState: string
  suggested: boolean
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

const recipeLabel = (row: Row): string => row.lines.map(({ quantity, unit }) => quantityLabel(quantity, unit)).join(' + ')

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')

  return token ? { Authorization: `Bearer ${token}` } : {}
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
  const [ticked, setTicked] = useState<Set<string>>(new Set())
  const [loadFailed, setLoadFailed] = useState(false)
  const [sending, setSending] = useState(false)
  const [notAdded, setNotAdded] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoadFailed(false)
    try {
      const response = await fetch(`${mealIri}/grocery_preview`, { headers: { Accept: 'application/json', ...authHeaders() } })
      if (!response.ok) throw new Error(`grocery_preview answered ${response.status}`)
      const loaded = rowsOf(((await response.json()) as Preview).ingredients)

      if (loaded.length === 0) {
        onDone()
        return
      }
      setRows(loaded)
      setTicked(new Set(loaded.filter((row) => row.suggested).map((row) => row.ingredientId)))
    } catch {
      setLoadFailed(true)
    }
  }, [mealIri, onDone])

  useEffect(() => {
    load()
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
      onDone()
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
          <Alert severity="error">Le repas est créé, mais la liste de ses ingrédients n’a pas pu être chargée.</Alert>
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
                  Recette : {recipeLabel(row)}
                </Typography>
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
