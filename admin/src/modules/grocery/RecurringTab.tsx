import { useEffect, useMemo, useState } from 'react'
import { useDataProvider } from 'react-admin'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { formatBuyAfter } from './groceryDates'
import { RecurrenceSelect } from './RecurrenceSelect'
import { bareId } from './recurring'
import type { Frequency } from './recurring'
import type { RecurringItems } from './useRecurringItems'

interface ProductOption {
  id: string
  name: string
}

/** « Produit récurrent » dialog of the grocery list: what comes back on its own, how often, and when next. */
export const RecurringTab = ({ recurring }: { recurring: RecurringItems }) => {
  const dataProvider = useDataProvider()
  const [products, setProducts] = useState<ProductOption[]>([])
  const [product, setProduct] = useState<ProductOption | null>(null)
  const [frequency, setFrequency] = useState<Frequency>('weekly')
  const [adding, setAdding] = useState(false)
  const { items, failed, byProduct } = recurring

  useEffect(() => {
    dataProvider
      .getList('products', { pagination: { page: 1, perPage: 500 }, sort: { field: 'name', order: 'ASC' }, filter: {} })
      .then(({ data }) => setProducts(data as ProductOption[]))
      .catch(() => {
        // Non-blocking: the tab still lists and edits what is recurring.
      })
  }, [dataProvider])

  const options = useMemo(() => products.filter((p) => !byProduct.has(bareId(p.id))), [products, byProduct])

  const add = async () => {
    if (!product) return
    setAdding(true)
    const ok = await recurring.setProductFrequency(bareId(product.id), frequency)
    setAdding(false)
    if (ok) {
      setProduct(null)
      setFrequency('weekly')
    }
  }

  return (
    <Box data-testid="recurring-tab">
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', flexWrap: 'wrap', mb: 2 }}>
        <Autocomplete
          options={options}
          value={product}
          onChange={(_e, value) => setProduct(value)}
          getOptionLabel={(option) => option.name}
          isOptionEqualToValue={(option, value) => option.id === value.id}
          renderInput={(params) => <TextField {...params} label="Produit à répéter" size="small" />}
          noOptionsText="Aucun produit"
          sx={{ flex: '1 1 220px' }}
        />
        <RecurrenceSelect
          value={frequency}
          onChange={(f) => f && setFrequency(f)}
          required
          size="small"
          inputLabel="Fréquence du nouvel article"
        />
        <Button startIcon={<AddIcon />} variant="contained" onClick={add} disabled={!product || adding}>
          Ajouter
        </Button>
      </Box>

      {failed && items === null && <Alert severity="error">Impossible de lire les articles récurrents.</Alert>}
      {items === null && !failed && (
        <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {items?.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 4 }}>
          Aucun article récurrent. Ajoutez un produit pour qu’il revienne seul sur la liste.
        </Typography>
      )}
      {items && items.length > 0 && (
        <List dense>
          {items.map((item) => (
            <ListItem
              key={item.id}
              data-testid="recurring-item"
              sx={{ gap: 1, flexWrap: 'wrap' }}
              secondaryAction={
                <IconButton
                  edge="end"
                  size="small"
                  aria-label={`Retirer ${item.label} des articles récurrents`}
                  onClick={() => recurring.remove(item)}
                >
                  <DeleteOutlineIcon fontSize="small" />
                </IconButton>
              }
            >
              <ListItemText
                primary={item.label}
                secondary={item.nextDueAt ? `Prochaine échéance : ${formatBuyAfter(item.nextDueAt)}` : undefined}
                sx={{ flex: '1 1 160px' }}
              />
              <RecurrenceSelect
                value={item.frequency}
                onChange={(f) => f && recurring.changeFrequency(item, f)}
                required
                size="small"
                inputLabel={`Fréquence de ${item.label}`}
              />
            </ListItem>
          ))}
        </List>
      )}
    </Box>
  )
}
