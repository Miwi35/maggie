import { useEffect, useState } from 'react'
import { useInput } from 'react-admin'
import { useWatch } from 'react-hook-form'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormHelperText from '@mui/material/FormHelperText'
import FormLabel from '@mui/material/FormLabel'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import { CategoryGlyph, CategoryIcon } from './CategoryGlyph'
import { filterCategoryIcons, findCategoryIcon, normalizeSearch } from './categoryIconCatalog'

interface CategoryIconPickerProps {
  source: string
  label: string
  helperText?: string
}

const TILE_MIN_WIDTH = 96
const TILE_HEIGHT = 84
const GAP = 8
const ROW_HEIGHT = TILE_HEIGHT + GAP
const OVERSCAN_ROWS = 3
// What a browser without layout (jsdom) reports as zero.
const FALLBACK_COLUMNS = 4
const FALLBACK_HEIGHT = 480

const tileSx = (selected: boolean) => ({
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 0.5,
  p: 1,
  height: TILE_HEIGHT,
  minWidth: 0,
  borderRadius: 2,
  border: selected ? '2px solid' : '1px solid',
  borderColor: selected ? 'primary.main' : 'divider',
  bgcolor: selected ? 'action.selected' : 'transparent',
})

interface Tile {
  /** `null` is the « no icon » tile. */
  name: string | null
  label: string
}

const NONE: Tile = { name: null, label: 'Aucune icône' }

interface IconGridProps {
  tiles: Tile[]
  current: string | null
  onChoose: (name: string | null) => void
}

/**
 * The tiles in a scroll box that only renders the rows in view (about two thousand icons would
 * freeze the dialog): rows are placed absolutely inside a spacer as tall as the whole grid.
 */
const IconGrid = ({ tiles, current, onChoose }: IconGridProps) => {
  const [viewport, setViewport] = useState<HTMLDivElement | null>(null)
  const [size, setSize] = useState({ width: 0, height: 0 })
  const [scrollTop, setScrollTop] = useState(0)

  useEffect(() => {
    if (viewport === null) {
      return undefined
    }
    const measure = () => setSize({ width: viewport.clientWidth, height: viewport.clientHeight })
    measure()
    if (typeof ResizeObserver === 'undefined') {
      return undefined
    }
    const observer = new ResizeObserver(measure)
    observer.observe(viewport)

    return () => observer.disconnect()
  }, [viewport])

  const columns = size.width > 0 ? Math.max(1, Math.floor((size.width + GAP) / (TILE_MIN_WIDTH + GAP))) : FALLBACK_COLUMNS
  const height = size.height > 0 ? size.height : FALLBACK_HEIGHT
  const rowCount = Math.ceil(tiles.length / columns)
  const first = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN_ROWS)
  const last = Math.min(rowCount - 1, Math.ceil((scrollTop + height) / ROW_HEIGHT) + OVERSCAN_ROWS)
  const rows: number[] = []
  for (let row = first; row <= last; row += 1) {
    rows.push(row)
  }

  const isCurrent = (name: string | null) =>
    current === null ? name === null : name !== null && name.toLowerCase() === current.toLowerCase()

  return (
    <Box
      ref={setViewport}
      data-testid="category-icon-grid"
      onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
      sx={{ height: '50vh', minHeight: 240, overflowY: 'auto', position: 'relative' }}
    >
      <Box sx={{ height: rowCount * ROW_HEIGHT, position: 'relative' }}>
        {rows.map((row) => (
          <Box
            key={row}
            sx={{
              position: 'absolute',
              top: row * ROW_HEIGHT,
              left: 0,
              right: 0,
              display: 'grid',
              gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
              gap: `${GAP}px`,
            }}
          >
            {tiles.slice(row * columns, (row + 1) * columns).map((tile) => (
              <ButtonBase
                key={tile.name ?? 'none'}
                aria-label={tile.label}
                aria-pressed={isCurrent(tile.name)}
                onClick={() => onChoose(tile.name)}
                sx={tileSx(isCurrent(tile.name))}
              >
                {tile.name === null ? <CloseIcon color="action" /> : <CategoryIcon name={tile.name} />}
                <Typography
                  variant="caption"
                  align="center"
                  sx={{ maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                >
                  {tile.label}
                </Typography>
              </ButtonBase>
            ))}
          </Box>
        ))}
      </Box>
    </Box>
  )
}

/**
 * A category's icon chosen, not typed: a button showing the current one, which opens a box with a
 * search field and a grid of icons with their French label. The stored value stays the icon's
 * name (« shopping-cart »), or null for none. A name outside the catalogue is kept and listed
 * first, selected, so opening the box never loses it.
 */
export const CategoryIconPicker = ({ source, label, helperText }: CategoryIconPickerProps) => {
  const { field } = useInput({ source })
  const color = useWatch({ name: 'color' }) as string | null | undefined
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')

  const current = typeof field.value === 'string' && field.value.trim() !== '' ? field.value.trim() : null
  const known = findCategoryIcon(current)
  const currentLabel = current === null ? 'Aucune' : (known?.label ?? current)

  const icons = ((): Tile[] => {
    if (!open) {
      return []
    }
    const found = filterCategoryIcons(query).map((entry) => ({ name: entry.name, label: entry.label }))
    const unknownShown = current !== null && known === undefined && (query.trim() === '' || normalizeSearch(current).includes(normalizeSearch(query)))

    return unknownShown ? [{ name: current, label: current }, ...found] : found
  })()

  const close = () => {
    setOpen(false)
    setQuery('')
  }

  const choose = (name: string | null) => {
    field.onChange(name)
    close()
  }

  return (
    <Box sx={{ width: '100%', mb: 2 }}>
      <FormLabel component="div" sx={{ mb: 1 }}>{label}</FormLabel>
      <Button
        variant="outlined"
        color="inherit"
        onClick={() => setOpen(true)}
        aria-label={`${label} : ${currentLabel}`}
        aria-haspopup="dialog"
        sx={{ justifyContent: 'flex-start', textTransform: 'none' }}
      >
        <CategoryGlyph color={color} icon={current} />
        {currentLabel}
      </Button>
      {helperText && <FormHelperText>{helperText}</FormHelperText>}

      <Dialog open={open} onClose={close} fullWidth maxWidth="sm" aria-labelledby="category-icon-title">
        <DialogTitle id="category-icon-title">Choisir une icône</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            size="small"
            label="Rechercher une icône"
            placeholder="courses, voiture, restaurant…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            sx={{ mt: 1, mb: 2 }}
          />
          <IconGrid key={query} tiles={[NONE, ...icons]} current={current} onChoose={choose} />
          {icons.length === 0 && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
              Aucune icône ne correspond.
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>Annuler</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
