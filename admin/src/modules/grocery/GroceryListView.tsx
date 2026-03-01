import { useState, useEffect, useCallback, useMemo } from 'react'
import { useDataProvider, useNotify, Title } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { useItemTransitions, transitionSx } from '../../hooks/useItemTransitions'
import Autocomplete from '@mui/material/Autocomplete'
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

interface ProductOption {
  id: string
  name: string
  category: string
  defaultUnit?: string
  preferredStore?: { id: string; name: string } | string
}

interface StoreOption {
  id: string
  name: string
}

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

const categoryChoices = [
  { id: 'produce', name: 'Fruits & Légumes' },
  { id: 'dairy', name: 'Produits laitiers' },
  { id: 'meat', name: 'Viandes' },
  { id: 'fish', name: 'Poissons' },
  { id: 'grain', name: 'Céréales' },
  { id: 'spice', name: 'Épices' },
  { id: 'condiment', name: 'Condiments' },
  { id: 'frozen', name: 'Surgelés' },
  { id: 'beverage', name: 'Boissons' },
  { id: 'household', name: 'Maison' },
  { id: 'hygiene', name: 'Hygiène' },
  { id: 'cleaning', name: 'Entretien' },
  { id: 'other', name: 'Autre' },
]

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
  const [products, setProducts] = useState<ProductOption[]>([])
  const [stores, setStores] = useState<StoreOption[]>([])
  const [newItemCategory, setNewItemCategory] = useState('')
  const [selectedStore, setSelectedStore] = useState<StoreOption | null>(null)
  const [storeInputValue, setStoreInputValue] = useState('')
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

  // Fetch products & stores when add dialog opens
  useEffect(() => {
    if (!addDialogOpen) return
    const fetchOptions = async () => {
      try {
        const [prodResult, storeResult] = await Promise.all([
          dataProvider.getList('products', { pagination: { page: 1, perPage: 500 }, sort: { field: 'name', order: 'ASC' }, filter: {} }),
          dataProvider.getList('stores', { pagination: { page: 1, perPage: 100 }, sort: { field: 'name', order: 'ASC' }, filter: {} }),
        ])
        setProducts(prodResult.data as ProductOption[])
        setStores(storeResult.data as StoreOption[])
      } catch {
        // Non-blocking: autocomplete will work without options
      }
    }
    fetchOptions()
  }, [addDialogOpen, dataProvider])

  const filteredProducts = useMemo(() => {
    if (newItemLabel.length < 2) return []
    const lower = newItemLabel.toLowerCase()
    return products.filter((p) => p.name.toLowerCase().includes(lower)).slice(0, 10)
  }, [newItemLabel, products])

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
      const payload: Record<string, unknown> = {
        label: newItemLabel,
        quantity: newItemQuantity ? parseFloat(newItemQuantity) : null,
        unit: newItemUnit || null,
      }
      if (newItemCategory) {
        payload.category = newItemCategory
      }
      if (selectedStore) {
        payload.storeId = selectedStore.id
      } else if (storeInputValue.trim()) {
        payload.storeName = storeInputValue.trim()
      }
      await fetch(`${entrypoint}/grocery/add-item`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(payload),
      })
      setAddDialogOpen(false)
      setNewItemLabel('')
      setNewItemQuantity('')
      setNewItemUnit('')
      setNewItemCategory('')
      setSelectedStore(null)
      setStoreInputValue('')
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

  // Track item additions/removals for animations
  const { addedIds, removingItems } = useItemTransitions(
    groceryList?.items ?? [],
    useCallback((item: GroceryItem) => item.id, []),
  )
  const removingIds = useMemo(() => new Set(removingItems.map((i) => i.id)), [removingItems])

  // Merge current items + ghost (removing) items for display
  const displayItems = useMemo(() => {
    const current = groceryList?.items ?? []
    const currentIds = new Set(current.map((i) => i.id))
    const ghosts = removingItems.filter((ri) => !currentIds.has(ri.id))
    return [...current, ...ghosts]
  }, [groceryList?.items, removingItems])

  // Group items by store, sorted by visitOrder
  const storeGroups: StoreGroup[] = []
  if (displayItems.length > 0) {
    const groupMap: Record<string, StoreGroup> = {}
    for (const item of displayItems) {
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

  // Counts exclude ghost (removing) items
  const checkedCount = groceryList?.items?.filter((i) => i.checked).length || 0
  const totalCount = groceryList?.items?.length || 0

  return (
    <Box sx={{ p: 2, maxWidth: 800, mx: 'auto' }}>
      <Title title="Courses" />

      <Paper sx={{ p: 2 }}>
        {loading && !groceryList && (
          <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
            <CircularProgress />
          </Box>
        )}

        {groceryList && (
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
              const realItems = group.items.filter((i) => !removingIds.has(i.id))
              const groupChecked = realItems.filter((i) => i.checked).length
              const groupTotal = realItems.length

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

                        const isRemoving = removingIds.has(item.id)

                        return (
                          <ListItem key={item.id} disablePadding sx={transitionSx(item.id, addedIds, removingIds)}>
                            <ListItemButton onClick={isRemoving ? undefined : () => handleCheck(item)} dense>
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
            <Autocomplete
              freeSolo
              options={filteredProducts}
              getOptionLabel={(option) => (typeof option === 'string' ? option : option.name)}
              inputValue={newItemLabel}
              onInputChange={(_e, value) => setNewItemLabel(value)}
              onChange={(_e, value) => {
                if (value && typeof value !== 'string') {
                  setNewItemLabel(value.name)
                  if (value.defaultUnit) setNewItemUnit(value.defaultUnit)
                  if (value.category) setNewItemCategory(value.category)
                  if (value.preferredStore) {
                    const ps = value.preferredStore
                    if (typeof ps === 'object' && ps.id) {
                      setSelectedStore(ps)
                      setStoreInputValue(ps.name)
                    } else if (typeof ps === 'string') {
                      const id = ps.includes('/') ? ps.split('/').pop()! : ps
                      const store = stores.find((s) => s.id === id)
                      if (store) {
                        setSelectedStore(store)
                        setStoreInputValue(store.name)
                      }
                    }
                  }
                }
              }}
              renderInput={(params) => <TextField {...params} label="Article" autoFocus />}
              renderOption={({ key, ...props }, option) => (
                <li key={key} {...props}>
                  <div>
                    <div>{typeof option === 'string' ? option : option.name}</div>
                    {typeof option !== 'string' && option.category && (
                      <div style={{ fontSize: '0.8em', color: '#888' }}>{option.category}</div>
                    )}
                  </div>
                </li>
              )}
              filterOptions={(x) => x}
              noOptionsText={newItemLabel.length < 2 ? 'Tapez au moins 2 caractères' : 'Nouveau produit'}
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
            <FormControl>
              <InputLabel>Catégorie</InputLabel>
              <Select
                value={newItemCategory}
                onChange={(e) => setNewItemCategory(e.target.value)}
                label="Catégorie"
              >
                <MenuItem value="">Aucune</MenuItem>
                {categoryChoices.map((c) => (
                  <MenuItem key={c.id} value={c.id}>
                    {c.name}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <Autocomplete
              freeSolo
              options={stores}
              getOptionLabel={(option) => (typeof option === 'string' ? option : option.name)}
              value={selectedStore}
              inputValue={storeInputValue}
              onInputChange={(_e, value) => setStoreInputValue(value)}
              onChange={(_e, value) => {
                if (value && typeof value !== 'string') {
                  setSelectedStore(value)
                } else {
                  setSelectedStore(null)
                }
              }}
              renderInput={(params) => <TextField {...params} label="Magasin" />}
              noOptionsText="Nouveau magasin"
              isOptionEqualToValue={(option, value) => option.id === value.id}
            />
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
