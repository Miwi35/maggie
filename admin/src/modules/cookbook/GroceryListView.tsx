import { useState, useEffect, useCallback } from 'react'
import { useDataProvider, useNotify, Title } from 'react-admin'
import Box from '@mui/material/Box'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import Button from '@mui/material/Button'
import ButtonGroup from '@mui/material/ButtonGroup'
import Checkbox from '@mui/material/Checkbox'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import ListSubheader from '@mui/material/ListSubheader'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogTitle from '@mui/material/DialogTitle'
import DialogContent from '@mui/material/DialogContent'
import DialogActions from '@mui/material/DialogActions'
import TextField from '@mui/material/TextField'
import Select from '@mui/material/Select'
import MenuItem from '@mui/material/MenuItem'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import CircularProgress from '@mui/material/CircularProgress'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import AddIcon from '@mui/icons-material/Add'

const STATUS_LABELS: Record<string, string> = {
  draft: 'Brouillon',
  active: 'Active',
  completed: 'Terminée',
}

const STATUS_COLORS: Record<string, 'default' | 'primary' | 'success'> = {
  draft: 'default',
  active: 'primary',
  completed: 'success',
}

const CATEGORY_LABELS: Record<string, string> = {
  produce: 'Fruits & Légumes',
  dairy: 'Produits laitiers',
  meat: 'Viandes',
  fish: 'Poissons',
  grain: 'Céréales',
  spice: 'Épices',
  condiment: 'Condiments',
  frozen: 'Surgelés',
  beverage: 'Boissons',
  household: 'Maison',
  hygiene: 'Hygiène',
  cleaning: 'Entretien',
  other: 'Autres',
}

interface GroceryItem {
  id: string
  '@id': string
  product?: { name: string; category: string }
  customLabel?: string
  quantity?: number
  unit?: string
  checked: boolean
  source: string
}

interface GroceryListData {
  id: string
  '@id': string
  weekStart: string
  status: string
  items: GroceryItem[]
}

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

export const GroceryListView = () => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const [lists, setLists] = useState<GroceryListData[]>([])
  const [activeList, setActiveList] = useState<GroceryListData | null>(null)
  const [loading, setLoading] = useState(false)
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [newItemLabel, setNewItemLabel] = useState('')
  const [newItemQuantity, setNewItemQuantity] = useState('')
  const [newItemUnit, setNewItemUnit] = useState('')

  const fetchLists = useCallback(async () => {
    setLoading(true)
    try {
      const { data } = await dataProvider.getList('grocery_lists', {
        pagination: { page: 1, perPage: 10 },
        sort: { field: 'createdAt', order: 'DESC' },
        filter: {},
      })
      setLists(data as GroceryListData[])
      const active = (data as GroceryListData[]).find((l) => l.status === 'active') || (data as GroceryListData[])[0]
      if (active) {
        // Fetch full details with items
        const { data: full } = await dataProvider.getOne('grocery_lists', { id: active.id })
        setActiveList(full as GroceryListData)
      }
    } catch {
      notify('Erreur lors du chargement', { type: 'error' })
    } finally {
      setLoading(false)
    }
  }, [dataProvider, notify])

  useEffect(() => {
    fetchLists()
  }, [fetchLists])

  const handleCheck = async (item: GroceryItem) => {
    try {
      const token = localStorage.getItem('token')
      await fetch(`${entrypoint.replace('/api', '')}${item['@id']}`, {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/merge-patch+json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ checked: !item.checked }),
      })
      // Refresh
      if (activeList) {
        const { data } = await dataProvider.getOne('grocery_lists', { id: activeList.id })
        setActiveList(data as GroceryListData)
      }
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  const handleAddItem = async () => {
    if (!activeList || !newItemLabel) return
    try {
      const token = localStorage.getItem('token')
      // Create a grocery item via the API
      await fetch(`${entrypoint}/grocery_items`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/ld+json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          groceryList: activeList['@id'],
          customLabel: newItemLabel,
          quantity: newItemQuantity ? parseFloat(newItemQuantity) : null,
          unit: newItemUnit || null,
          source: 'manual',
          checked: false,
        }),
      })
      setAddDialogOpen(false)
      setNewItemLabel('')
      setNewItemQuantity('')
      setNewItemUnit('')
      // Refresh list
      const { data } = await dataProvider.getOne('grocery_lists', { id: activeList.id })
      setActiveList(data as GroceryListData)
      notify('Article ajouté', { type: 'success' })
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  const handleStatusChange = async (status: string) => {
    if (!activeList) return
    try {
      await dataProvider.update('grocery_lists', {
        id: activeList.id,
        data: { status },
        previousData: activeList,
      })
      fetchLists()
      notify('Statut mis à jour', { type: 'success' })
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  const selectList = async (list: GroceryListData) => {
    try {
      const { data } = await dataProvider.getOne('grocery_lists', { id: list.id })
      setActiveList(data as GroceryListData)
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  // Group items by category
  const groupedItems: Record<string, GroceryItem[]> = {}
  if (activeList?.items) {
    for (const item of activeList.items) {
      const category = item.product?.category || 'other'
      if (!groupedItems[category]) groupedItems[category] = []
      groupedItems[category].push(item)
    }
  }

  const sortedCategories = Object.keys(groupedItems).sort((a, b) => {
    const la = CATEGORY_LABELS[a] || a
    const lb = CATEGORY_LABELS[b] || b
    return la.localeCompare(lb)
  })

  const checkedCount = activeList?.items?.filter((i) => i.checked).length || 0
  const totalCount = activeList?.items?.length || 0

  return (
    <Box sx={{ p: 2 }}>
      <Title title="Courses" />

      <Box sx={{ display: 'flex', gap: 3 }}>
        {/* Sidebar: list of grocery lists */}
        <Paper sx={{ p: 2, minWidth: 250 }}>
          <Typography variant="h6" gutterBottom>
            Listes de courses
          </Typography>
          {lists.map((list) => (
            <Box
              key={list.id}
              onClick={() => selectList(list)}
              sx={{
                p: 1.5,
                mb: 1,
                borderRadius: 1,
                cursor: 'pointer',
                bgcolor: activeList?.id === list.id ? 'primary.light' : 'transparent',
                color: activeList?.id === list.id ? 'primary.contrastText' : 'text.primary',
                '&:hover': { bgcolor: activeList?.id === list.id ? 'primary.light' : 'action.hover' },
              }}
            >
              <Typography variant="body2" fontWeight={500}>
                Semaine du {new Date(list.weekStart).toLocaleDateString('fr-FR')}
              </Typography>
              <Chip
                label={STATUS_LABELS[list.status] || list.status}
                color={STATUS_COLORS[list.status] || 'default'}
                size="small"
                sx={{ mt: 0.5 }}
              />
            </Box>
          ))}
          {lists.length === 0 && !loading && (
            <Typography variant="body2" color="text.secondary">
              Aucune liste. Demandez à Maggie d'en générer une !
            </Typography>
          )}
        </Paper>

        {/* Main: checklist */}
        <Paper sx={{ p: 2, flex: 1 }}>
          {loading && (
            <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
              <CircularProgress />
            </Box>
          )}

          {!loading && activeList && (
            <>
              <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <ShoppingCartIcon color="primary" />
                  <Typography variant="h6">
                    Semaine du {new Date(activeList.weekStart).toLocaleDateString('fr-FR')}
                  </Typography>
                  <Chip
                    label={`${checkedCount}/${totalCount}`}
                    size="small"
                    color={checkedCount === totalCount && totalCount > 0 ? 'success' : 'default'}
                  />
                </Box>
                <Box sx={{ display: 'flex', gap: 1 }}>
                  <Button startIcon={<AddIcon />} size="small" onClick={() => setAddDialogOpen(true)}>
                    Ajouter
                  </Button>
                  <ButtonGroup size="small">
                    {Object.entries(STATUS_LABELS).map(([value, label]) => (
                      <Button
                        key={value}
                        variant={activeList.status === value ? 'contained' : 'outlined'}
                        onClick={() => handleStatusChange(value)}
                      >
                        {label}
                      </Button>
                    ))}
                  </ButtonGroup>
                </Box>
              </Box>

              {sortedCategories.map((category) => (
                <List
                  key={category}
                  subheader={
                    <ListSubheader sx={{ bgcolor: 'transparent', fontWeight: 600 }}>
                      {CATEGORY_LABELS[category] || category}
                    </ListSubheader>
                  }
                  dense
                >
                  {groupedItems[category].map((item) => {
                    const label = item.customLabel || item.product?.name || 'Article'
                    const detail =
                      item.quantity != null ? `${item.quantity}${item.unit ? ' ' + item.unit : ''}` : ''

                    return (
                      <ListItem key={item.id} disablePadding>
                        <ListItemButton onClick={() => handleCheck(item)} dense>
                          <ListItemIcon>
                            <Checkbox edge="start" checked={item.checked} tabIndex={-1} disableRipple />
                          </ListItemIcon>
                          <ListItemText
                            primary={label}
                            secondary={detail}
                            sx={{
                              textDecoration: item.checked ? 'line-through' : 'none',
                              opacity: item.checked ? 0.5 : 1,
                            }}
                          />
                          <Chip
                            label={item.source === 'recipe' ? 'Recette' : item.source === 'recurring' ? 'Récurrent' : 'Manuel'}
                            size="small"
                            variant="outlined"
                            sx={{ ml: 1 }}
                          />
                        </ListItemButton>
                      </ListItem>
                    )
                  })}
                </List>
              ))}

              {totalCount === 0 && (
                <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 4 }}>
                  Cette liste est vide.
                </Typography>
              )}
            </>
          )}

          {!loading && !activeList && (
            <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 4 }}>
              Sélectionnez une liste ou demandez à Maggie d'en générer une.
            </Typography>
          )}
        </Paper>
      </Box>

      {/* Add item dialog */}
      <Dialog open={addDialogOpen} onClose={() => setAddDialogOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Ajouter un article</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
            <TextField label="Article" value={newItemLabel} onChange={(e) => setNewItemLabel(e.target.value)} autoFocus />
            <TextField label="Quantité" type="number" value={newItemQuantity} onChange={(e) => setNewItemQuantity(e.target.value)} />
            <FormControl>
              <InputLabel>Unité</InputLabel>
              <Select value={newItemUnit} onChange={(e) => setNewItemUnit(e.target.value)} label="Unité">
                <MenuItem value="">Aucune</MenuItem>
                <MenuItem value="g">g</MenuItem>
                <MenuItem value="kg">kg</MenuItem>
                <MenuItem value="ml">ml</MenuItem>
                <MenuItem value="l">l</MenuItem>
                <MenuItem value="cl">cl</MenuItem>
                <MenuItem value="piece">pièce</MenuItem>
                <MenuItem value="bunch">botte</MenuItem>
                <MenuItem value="can">boîte</MenuItem>
                <MenuItem value="bottle">bouteille</MenuItem>
                <MenuItem value="pack">paquet</MenuItem>
                <MenuItem value="sachet">sachet</MenuItem>
              </Select>
            </FormControl>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAddDialogOpen(false)}>Annuler</Button>
          <Button onClick={handleAddItem} variant="contained" disabled={!newItemLabel}>
            Ajouter
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
