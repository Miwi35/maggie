import { useState, useEffect, useCallback } from 'react'
import { useDataProvider, useNotify, Title } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import Box from '@mui/material/Box'
import Paper from '@mui/material/Paper'
import Typography from '@mui/material/Typography'
import Button from '@mui/material/Button'
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
import Collapse from '@mui/material/Collapse'
import IconButton from '@mui/material/IconButton'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import AddIcon from '@mui/icons-material/Add'
import ExpandLess from '@mui/icons-material/ExpandLess'
import ExpandMore from '@mui/icons-material/ExpandMore'
import DoneAllIcon from '@mui/icons-material/DoneAll'

interface GroceryItem {
  id: string
  '@id': string
  label: string
  product?: string
  customLabel?: string
  quantity?: number
  unit?: string
  checked: boolean
  source: string
  store?: { id: string; name: string; visitOrder: number }
  buyAfter?: string
}

interface GroceryListData {
  id: string
  '@id': string
  items: GroceryItem[]
}

interface StoreGroup {
  storeId: string | null
  storeName: string
  visitOrder: number
  items: GroceryItem[]
}

const GROCERY_LIST_TOPICS = ['/api/grocery_lists/{id}']
const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

export const GroceryListView = () => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const [groceryList, setGroceryList] = useState<GroceryListData | null>(null)
  const [loading, setLoading] = useState(false)
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [endErrandDialogOpen, setEndErrandDialogOpen] = useState(false)
  const [uncheckedItems, setUncheckedItems] = useState<GroceryItem[]>([])
  const [newItemLabel, setNewItemLabel] = useState('')
  const [newItemQuantity, setNewItemQuantity] = useState('')
  const [newItemUnit, setNewItemUnit] = useState('')
  const [collapsedStores, setCollapsedStores] = useState<Set<string>>(new Set())

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const { data } = await dataProvider.getList('grocery_lists', {
        pagination: { page: 1, perPage: 1 },
        sort: { field: 'createdAt', order: 'DESC' },
        filter: {},
      })
      const list = (data as GroceryListData[])[0]
      if (list) {
        const { data: full } = await dataProvider.getOne('grocery_lists', { id: list.id })
        setGroceryList(full as GroceryListData)
      }
    } catch {
      notify('Erreur lors du chargement', { type: 'error' })
    } finally {
      setLoading(false)
    }
  }, [dataProvider, notify])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  useMercure(GROCERY_LIST_TOPICS, fetchList)

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
      if (groceryList) {
        const { data } = await dataProvider.getOne('grocery_lists', { id: groceryList.id })
        setGroceryList(data as GroceryListData)
      }
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  const handleAddItem = async () => {
    if (!groceryList || !newItemLabel) return
    try {
      const token = localStorage.getItem('token')
      await fetch(`${entrypoint}/grocery/add-item`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          label: newItemLabel,
          quantity: newItemQuantity ? parseFloat(newItemQuantity) : null,
          unit: newItemUnit || null,
        }),
      })
      setAddDialogOpen(false)
      setNewItemLabel('')
      setNewItemQuantity('')
      setNewItemUnit('')
      const { data } = await dataProvider.getOne('grocery_lists', { id: groceryList.id })
      setGroceryList(data as GroceryListData)
      notify('Article ajouté', { type: 'success' })
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  const handleEndErrand = async () => {
    if (!groceryList) return
    try {
      const token = localStorage.getItem('token')
      // Remove checked items
      const checkedItems = groceryList.items.filter((i) => i.checked)
      for (const item of checkedItems) {
        await fetch(`${entrypoint.replace('/api', '')}${item['@id']}`, {
          method: 'DELETE',
          headers: { Authorization: `Bearer ${token}` },
        })
      }
      const remaining = groceryList.items.filter((i) => !i.checked)
      setUncheckedItems(remaining)
      setEndErrandDialogOpen(true)
      const { data } = await dataProvider.getOne('grocery_lists', { id: groceryList.id })
      setGroceryList(data as GroceryListData)
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  const handleRemoveItem = async (item: GroceryItem) => {
    try {
      const token = localStorage.getItem('token')
      await fetch(`${entrypoint.replace('/api', '')}${item['@id']}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      })
      setUncheckedItems((prev) => prev.filter((i) => i.id !== item.id))
      if (groceryList) {
        const { data } = await dataProvider.getOne('grocery_lists', { id: groceryList.id })
        setGroceryList(data as GroceryListData)
      }
      notify('Article retiré', { type: 'success' })
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  const toggleStoreCollapse = (storeKey: string) => {
    setCollapsedStores((prev) => {
      const next = new Set(prev)
      if (next.has(storeKey)) {
        next.delete(storeKey)
      } else {
        next.add(storeKey)
      }
      return next
    })
  }

  // Group items by store, sorted by visitOrder
  const storeGroups: StoreGroup[] = []
  if (groceryList?.items) {
    const groupMap: Record<string, StoreGroup> = {}
    for (const item of groceryList.items) {
      const storeKey = item.store?.id || '__unassigned__'
      if (!groupMap[storeKey]) {
        groupMap[storeKey] = {
          storeId: item.store?.id || null,
          storeName: item.store?.name || 'Non assigné',
          visitOrder: item.store?.visitOrder ?? Number.MAX_SAFE_INTEGER,
          items: [],
        }
      }
      groupMap[storeKey].items.push(item)
    }
    storeGroups.push(...Object.values(groupMap).sort((a, b) => a.visitOrder - b.visitOrder))
  }

  const checkedCount = groceryList?.items?.filter((i) => i.checked).length || 0
  const totalCount = groceryList?.items?.length || 0

  return (
    <Box sx={{ p: 2, maxWidth: 800, mx: 'auto' }}>
      <Title title="Courses" />

      <Paper sx={{ p: 2 }}>
        {loading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
            <CircularProgress />
          </Box>
        )}

        {!loading && groceryList && (
          <>
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <ShoppingCartIcon color="primary" />
                <Typography variant="h6">Ma liste de courses</Typography>
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
                <Button
                  startIcon={<DoneAllIcon />}
                  size="small"
                  color="success"
                  variant="outlined"
                  onClick={handleEndErrand}
                  disabled={checkedCount === 0}
                >
                  Terminer les courses
                </Button>
              </Box>
            </Box>

            {storeGroups.map((group) => {
              const storeKey = group.storeId || '__unassigned__'
              const isCollapsed = collapsedStores.has(storeKey)
              const groupChecked = group.items.filter((i) => i.checked).length
              const groupTotal = group.items.length

              return (
                <Box key={storeKey} sx={{ mb: 1 }}>
                  <List
                    dense
                    subheader={
                      <ListSubheader
                        sx={{
                          bgcolor: 'transparent',
                          fontWeight: 600,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                          cursor: 'pointer',
                        }}
                        onClick={() => toggleStoreCollapse(storeKey)}
                      >
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                          {group.storeName}
                          <Chip label={`${groupChecked}/${groupTotal}`} size="small" variant="outlined" />
                        </Box>
                        <IconButton size="small">{isCollapsed ? <ExpandMore /> : <ExpandLess />}</IconButton>
                      </ListSubheader>
                    }
                  >
                    <Collapse in={!isCollapsed}>
                      {group.items.map((item) => {
                        const label = item.label
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
                                label={
                                  item.source === 'recipe'
                                    ? 'Recette'
                                    : item.source === 'recurring'
                                      ? 'Récurrent'
                                      : 'Manuel'
                                }
                                size="small"
                                variant="outlined"
                                sx={{ ml: 1 }}
                              />
                            </ListItemButton>
                          </ListItem>
                        )
                      })}
                    </Collapse>
                  </List>
                </Box>
              )
            })}

            {totalCount === 0 && (
              <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 4 }}>
                Cette liste est vide.
              </Typography>
            )}
          </>
        )}

        {!loading && !groceryList && (
          <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center', py: 4 }}>
            Aucune liste. Demandez à Maggie d'en créer une !
          </Typography>
        )}
      </Paper>

      {/* Add item dialog */}
      <Dialog open={addDialogOpen} onClose={() => setAddDialogOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Ajouter un article</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
            <TextField
              label="Article"
              value={newItemLabel}
              onChange={(e) => setNewItemLabel(e.target.value)}
              autoFocus
            />
            <TextField
              label="Quantité"
              type="number"
              value={newItemQuantity}
              onChange={(e) => setNewItemQuantity(e.target.value)}
            />
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

      {/* End errand dialog */}
      <Dialog
        open={endErrandDialogOpen}
        onClose={() => setEndErrandDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Articles restants</DialogTitle>
        <DialogContent>
          {uncheckedItems.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Tous les articles ont été achetés !
            </Typography>
          ) : (
            <List dense>
              {uncheckedItems.map((item) => {
                const label = item.label
                return (
                  <ListItem
                    key={item.id}
                    secondaryAction={
                      <Button size="small" color="error" onClick={() => handleRemoveItem(item)}>
                        Retirer
                      </Button>
                    }
                  >
                    <ListItemText primary={label} secondary={item.store?.name} />
                  </ListItem>
                )
              })}
            </List>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEndErrandDialogOpen(false)} variant="contained">
            Fermer
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
