import { useState } from 'react'
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
import LabelIcon from '@mui/icons-material/Label'
import { CATEGORY_ICONS, CategoryGlyph } from './CategoryGlyph'
import { filterCategoryIcons, findCategoryIcon, normalizeSearch } from './categoryIconCatalog'

interface CategoryIconPickerProps {
  source: string
  label: string
  helperText?: string
}

const tileSx = (selected: boolean) => ({
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 0.5,
  p: 1,
  minHeight: 84,
  borderRadius: 2,
  border: selected ? '2px solid' : '1px solid',
  borderColor: selected ? 'primary.main' : 'divider',
  bgcolor: selected ? 'action.selected' : 'transparent',
})

interface Tile {
  name: string
  label: string
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
  const isCurrent = (name: string) => current !== null && name.toLowerCase() === current.toLowerCase()
  const currentLabel = current === null ? 'Aucune' : (known?.label ?? current)

  const tiles = ((): Tile[] => {
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
          <Box
            sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))', gap: 1 }}
          >
            <ButtonBase
              aria-label="Aucune icône"
              aria-pressed={current === null}
              onClick={() => choose(null)}
              sx={tileSx(current === null)}
            >
              <CloseIcon color="action" />
              <Typography variant="caption" align="center">Aucune icône</Typography>
            </ButtonBase>
            {tiles.map((tile) => {
              const Icon = CATEGORY_ICONS[tile.name] ?? LabelIcon

              return (
                <ButtonBase
                  key={tile.name}
                  aria-label={tile.label}
                  aria-pressed={isCurrent(tile.name)}
                  onClick={() => choose(tile.name)}
                  sx={tileSx(isCurrent(tile.name))}
                >
                  <Icon />
                  <Typography variant="caption" align="center" sx={{ overflowWrap: 'anywhere' }}>
                    {tile.label}
                  </Typography>
                </ButtonBase>
              )
            })}
          </Box>
          {tiles.length === 0 && (
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
