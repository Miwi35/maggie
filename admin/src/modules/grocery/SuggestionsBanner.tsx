import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Collapse from '@mui/material/Collapse'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import AddShoppingCartIcon from '@mui/icons-material/AddShoppingCart'
import ExpandLess from '@mui/icons-material/ExpandLess'
import ExpandMore from '@mui/icons-material/ExpandMore'
import { rhythmLabel } from './purchaseSuggestions'
import type { PurchaseSuggestion } from './purchaseSuggestions'

/**
 * « Souvent pris » (MAG-495): the products bought regularly and due again,
 * each a gesture away from the list — or from being left out until next time.
 */
export function SuggestionsBanner({
  suggestions,
  onAdd,
  onSnooze,
}: {
  suggestions: PurchaseSuggestion[]
  onAdd: (suggestion: PurchaseSuggestion) => Promise<void>
  onSnooze: (suggestion: PurchaseSuggestion) => Promise<void>
}) {
  const [open, setOpen] = useState(true)
  const [busy, setBusy] = useState<Set<string>>(new Set())

  if (suggestions.length === 0) return null

  // One request per row at a time: a second tap would add the product twice.
  const run = async (suggestion: PurchaseSuggestion, action: (s: PurchaseSuggestion) => Promise<void>) => {
    const id = suggestion.product.id
    setBusy((prev) => new Set(prev).add(id))
    try {
      await action(suggestion)
    } finally {
      setBusy((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
    }
  }

  return (
    <Box data-testid="grocery-suggestions" sx={{ mb: 2, border: 1, borderColor: 'divider', borderRadius: 1 }}>
      <List dense disablePadding>
        <ListItemButton onClick={() => setOpen((o) => !o)} dense aria-expanded={open}>
          <ListItemText primary={`Souvent pris (${suggestions.length})`} primaryTypographyProps={{ fontWeight: 600 }} />
          {open ? <ExpandLess /> : <ExpandMore />}
        </ListItemButton>
        <Collapse in={open} unmountOnExit>
          {suggestions.map((suggestion) => {
            const name = suggestion.product.name
            const disabled = busy.has(suggestion.product.id)
            return (
              <ListItem key={suggestion.product.id} data-testid="grocery-suggestion" sx={{ pr: 1 }}>
                <ListItemText
                  primary={`${name} · ${rhythmLabel(suggestion.everyDays)}`}
                  primaryTypographyProps={{ noWrap: true, title: name }}
                  sx={{ minWidth: 0 }}
                />
                <Box sx={{ display: 'flex', alignItems: 'center', flexShrink: 0, gap: 0.5 }}>
                  <Button
                    size="small"
                    color="inherit"
                    aria-label={`Pas besoin de ${name}`}
                    disabled={disabled}
                    onClick={() => run(suggestion, onSnooze)}
                  >
                    Pas besoin
                  </Button>
                  <IconButton
                    size="small"
                    color="primary"
                    aria-label={`Ajouter ${name}`}
                    disabled={disabled}
                    onClick={() => run(suggestion, onAdd)}
                  >
                    <AddShoppingCartIcon fontSize="small" />
                  </IconButton>
                </Box>
              </ListItem>
            )
          })}
        </Collapse>
      </List>
    </Box>
  )
}
