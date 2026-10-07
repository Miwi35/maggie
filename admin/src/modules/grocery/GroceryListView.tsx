import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { useDataProvider, useNotify, Title } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { useItemTransitions, transitionSx } from '../../hooks/useItemTransitions'
import { DndContext, closestCenter, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { SortableContext, useSortable, verticalListSortingStrategy, arrayMove } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
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
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import { QuantityStepper } from './QuantityStepper'

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
  position: number
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

// The REST API sends `buyAfter` as a date-time, Mercure as a plain date: the
// first ten characters are the day in both, compared with the local today.
const localToday = () => {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
const isDeferred = (item: GroceryItem, today: string) => !!item.buyAfter && item.buyAfter.slice(0, 10) > today
const formatBuyAfter = (buyAfter: string) => {
  const [y, m, d] = buyAfter.slice(0, 10).split('-')
  return `${d}/${m}/${y}`
}

// Taps on − / + within this delay become one request.
const QUANTITY_SAVE_DELAY_MS = 400

const GROCERY_LIST_TOPICS = ['/api/grocery_lists/{id}']
const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

// A line's own `@id` is an anonymous node (`GroceryItem` is no ApiResource): only its id routes.
const itemUrl = (item: GroceryItem) => `${entrypoint}/grocery_items/${item.id}`

function SortableGroceryItem({
  item,
  addedIds,
  removingIds,
  onCheck,
  onViewDetail,
  onQuantityChange,
  onQuantityInvalid,
  isDragDisabled,
}: {
  item: GroceryItem
  addedIds: Set<string>
  removingIds: Set<string>
  onCheck: (item: GroceryItem) => void
  onViewDetail: (item: GroceryItem) => void
  onQuantityChange: (item: GroceryItem, quantity: number) => void
  onQuantityInvalid: () => void
  isDragDisabled: boolean
}) {
  const isRemoving = removingIds.has(item.id)
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: item.id,
    disabled: isDragDisabled || isRemoving,
  })
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : undefined,
  }

  const label = item.label

  return (
    // `data-testid` and `data-store`: MUI renders a store's ListSubheader as an
    // `<li>` too, so `getByRole('listitem')` matches the headings as well as
    // the lines. The e2e journeys (MAG-101) address a line by this id and read
    // the order of the shop's own aisles from `data-store`.
    <ListItem
      ref={setNodeRef}
      style={style}
      disablePadding
      data-testid="grocery-item"
      data-store={item.store?.name ?? ''}
      sx={transitionSx(item.id, addedIds, removingIds)}
    >
      <IconButton
        size="small"
        sx={{ cursor: isDragDisabled ? 'default' : 'grab', ml: 0.5, mr: -0.5 }}
        {...attributes}
        {...listeners}
        data-testid="drag-handle"
        tabIndex={-1}
      >
        <DragIndicatorIcon fontSize="small" sx={{ color: 'action.disabled' }} />
      </IconButton>
      <ListItemButton onClick={isRemoving ? undefined : () => onViewDetail(item)} dense>
        <ListItemIcon>
          <Checkbox
            edge="start"
            checked={item.checked}
            tabIndex={-1}
            disableRipple
            onClick={(e) => {
              e.stopPropagation()
              if (!isRemoving) onCheck(item)
            }}
          />
        </ListItemIcon>
        <ListItemText
          primary={label}
          sx={{
            textDecoration: item.checked ? 'line-through' : 'none',
            opacity: item.checked ? 0.5 : 1,
          }}
        />
        <Chip
          label={
            item.source === 'recipe' ? 'Recette' : item.source === 'recurring' ? 'Récurrent' : 'Manuel'
          }
          size="small"
          variant="outlined"
          sx={{ ml: 1 }}
        />
      </ListItemButton>
      <QuantityStepper
        label={label}
        quantity={item.quantity}
        unit={item.unit}
        disabled={isRemoving}
        onChange={(quantity) => onQuantityChange(item, quantity)}
        onInvalid={onQuantityInvalid}
      />
    </ListItem>
  )
}

// The Hydra data provider puts the IRI (`/api/stores/01M3…`) in `record.id`; the API wants the bare ULID.
const bareId = (id: string) => id.split('/').pop() ?? id

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
  const [laterOpen, setLaterOpen] = useState(false)
  const [detailItem, setDetailItem] = useState<GroceryItem | null>(null)
  const [editLabel, setEditLabel] = useState('')
  const [editQuantity, setEditQuantity] = useState('')
  const [editUnit, setEditUnit] = useState('')
  const [editCategory, setEditCategory] = useState('')
  const [editSelectedStore, setEditSelectedStore] = useState<StoreOption | null>(null)
  const [editStoreInput, setEditStoreInput] = useState('')

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

  const handleMercure = useCallback(
    (data?: string) => {
      if (!data) { fetchList(); return }
      try {
        const payload = JSON.parse(data)
        const action = payload.action as string | undefined

        if (action === 'reorder' && Array.isArray(payload.items)) {
          const positionMap = new Map<string, number>(
            payload.items.map((i: { id: string; position: number }) => [i.id, i.position]),
          )
          setGroceryList((prev) =>
            prev
              ? {
                  ...prev,
                  items: prev.items.map((item) => {
                    const pos = positionMap.get(item.id)
                    return pos !== undefined ? { ...item, position: pos } : item
                  }),
                }
              : prev,
          )
        } else if (action === 'check' && payload.itemId) {
          setGroceryList((prev) =>
            prev
              ? {
                  ...prev,
                  items: prev.items.map((item) =>
                    item.id === payload.itemId ? { ...item, checked: payload.checked } : item,
                  ),
                }
              : prev,
          )
        } else if (action === 'remove' && payload.itemId) {
          setGroceryList((prev) =>
            prev ? { ...prev, items: prev.items.filter((item) => item.id !== payload.itemId) } : prev,
          )
        } else if (Array.isArray(payload.items)) {
          // Full data payload (create/update) — replace items
          setGroceryList((prev) => (prev ? { ...prev, items: payload.items } : prev))
        } else {
          fetchList()
        }
      } catch {
        fetchList()
      }
    },
    [fetchList],
  )
  useMercure(GROCERY_LIST_TOPICS, handleMercure)

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

  // Initialize edit state when detail item changes
  useEffect(() => {
    if (!detailItem) return
    setEditLabel(detailItem.label)
    setEditQuantity(detailItem.quantity != null ? String(detailItem.quantity) : '')
    setEditUnit(detailItem.unit ?? '')
    setEditCategory('')
    const s = detailItem.store
    if (s) {
      setEditSelectedStore({ id: s.id, name: s.name })
      setEditStoreInput(s.name)
    } else {
      setEditSelectedStore(null)
      setEditStoreInput('')
    }
    // Also fetch products/stores if not already loaded
    if (products.length === 0) {
      const fetchOptions = async () => {
        try {
          const [prodResult, storeResult] = await Promise.all([
            dataProvider.getList('products', { pagination: { page: 1, perPage: 500 }, sort: { field: 'name', order: 'ASC' }, filter: {} }),
            dataProvider.getList('stores', { pagination: { page: 1, perPage: 100 }, sort: { field: 'name', order: 'ASC' }, filter: {} }),
          ])
          setProducts(prodResult.data as ProductOption[])
          setStores(storeResult.data as StoreOption[])
        } catch {
          // Non-blocking
        }
      }
      fetchOptions()
    }
  }, [detailItem]) // eslint-disable-line react-hooks/exhaustive-deps

  const filteredEditProducts = useMemo(() => {
    if (editLabel.length < 2) return []
    const lower = editLabel.toLowerCase()
    return products.filter((p) => p.name.toLowerCase().includes(lower)).slice(0, 10)
  }, [editLabel, products])

  const filteredProducts = useMemo(() => {
    if (newItemLabel.length < 2) return []
    const lower = newItemLabel.toLowerCase()
    return products.filter((p) => p.name.toLowerCase().includes(lower)).slice(0, 10)
  }, [newItemLabel, products])

  const handleCheck = async (item: GroceryItem) => {
    try {
      const token = localStorage.getItem('token')
      const response = await fetch(itemUrl(item), {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/merge-patch+json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ checked: !item.checked }),
      })
      if (!response.ok) {
        notify("Erreur : l'article n'a pas été coché", { type: 'error' })
        return
      }
      if (groceryList) {
        const { data } = await dataProvider.getOne('grocery_lists', { id: groceryList.id })
        setGroceryList(data as GroceryListData)
      }
    } catch {
      notify('Erreur', { type: 'error' })
    }
  }

  // Quantities typed or tapped but not yet confirmed by the API, by item id: they
  // are what the line shows, so a Mercure echo of an earlier save cannot flash an
  // older value in the middle of a burst of taps.
  const [pendingQuantities, setPendingQuantities] = useState<Record<string, number>>({})
  const pendingRef = useRef<Record<string, number>>({})
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>())
  const saves = useRef(new Map<string, Promise<void>>())

  useEffect(() => {
    const pending = timers.current
    return () => pending.forEach((timer) => clearTimeout(timer))
  }, [])

  const saveQuantity = useCallback(
    async (item: GroceryItem, quantity: number) => {
      let saved = false
      try {
        const token = localStorage.getItem('token')
        const response = await fetch(`${entrypoint}/grocery/edit-item/${item.id}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
          body: JSON.stringify({ quantity }),
        })
        saved = response.ok
      } catch {
        saved = false
      }
      if (saved) {
        setGroceryList((prev) =>
          prev
            ? { ...prev, items: prev.items.map((i) => (i.id === item.id ? { ...i, quantity } : i)) }
            : prev,
        )
      } else {
        notify(`Erreur : la quantité de « ${item.label} » n'a pas été modifiée`, { type: 'error' })
      }
      // A newer tap keeps its own value; only the one just settled is dropped,
      // which on a refusal puts the previous quantity back on the line.
      if (pendingRef.current[item.id] === quantity) {
        const rest = { ...pendingRef.current }
        delete rest[item.id]
        pendingRef.current = rest
        setPendingQuantities(rest)
      }
    },
    [notify],
  )

  const handleQuantityChange = useCallback(
    (item: GroceryItem, quantity: number) => {
      pendingRef.current = { ...pendingRef.current, [item.id]: quantity }
      setPendingQuantities(pendingRef.current)

      clearTimeout(timers.current.get(item.id))
      timers.current.set(
        item.id,
        setTimeout(() => {
          timers.current.delete(item.id)
          const last = pendingRef.current[item.id]
          if (last === undefined) return
          // One save at a time per line, in order.
          const previous = saves.current.get(item.id) ?? Promise.resolve()
          const next = previous.then(() => saveQuantity(item, last))
          saves.current.set(item.id, next)
          next.finally(() => {
            if (saves.current.get(item.id) === next) saves.current.delete(item.id)
          })
        }, QUANTITY_SAVE_DELAY_MS),
      )
    },
    [saveQuantity],
  )

  const handleQuantityInvalid = useCallback(() => {
    notify('Quantité invalide : saisissez un nombre supérieur à 0', { type: 'warning' })
  }, [notify])

  const handleEditItem = async () => {
    if (!groceryList || !detailItem || !editLabel) return
    try {
      const token = localStorage.getItem('token')
      const payload: Record<string, unknown> = {
        label: editLabel,
        quantity: editQuantity ? parseFloat(editQuantity) : null,
        unit: editUnit || null,
      }
      if (editCategory) {
        payload.category = editCategory
      }
      if (editSelectedStore) {
        payload.storeId = bareId(editSelectedStore.id)
      } else if (editStoreInput.trim()) {
        payload.storeName = editStoreInput.trim()
      }
      const response = await fetch(`${entrypoint}/grocery/edit-item/${detailItem.id}`, {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(payload),
      })
      if (!response.ok) {
        notify("Erreur : l'article n'a pas été modifié", { type: 'error' })
        return
      }
      setDetailItem(null)
      const { data } = await dataProvider.getOne('grocery_lists', { id: groceryList.id })
      setGroceryList(data as GroceryListData)
      notify('Article modifié', { type: 'success' })
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
        payload.storeId = bareId(selectedStore.id)
      } else if (storeInputValue.trim()) {
        payload.storeName = storeInputValue.trim()
      }
      const response = await fetch(`${entrypoint}/grocery/add-item`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(payload),
      })
      if (!response.ok) {
        notify("Erreur : l'article n'a pas été ajouté", { type: 'error' })
        return
      }
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
        const response = await fetch(itemUrl(item), {
          method: 'DELETE',
          headers: { Authorization: `Bearer ${token}` },
        })
        if (!response.ok) {
          notify("Erreur : les courses n'ont pas pu être terminées", { type: 'error' })
          return
        }
      }
      const today = localToday()
      const remaining = groceryList.items.filter((i) => !i.checked && !isDeferred(i, today))
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
      const response = await fetch(itemUrl(item), {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!response.ok) {
        notify("Erreur : l'article n'a pas été retiré", { type: 'error' })
        return
      }
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

  // dnd-kit sensors
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }))

  const handleDragEnd = useCallback(
    async (event: DragEndEvent, groupItems: GroceryItem[]) => {
      const { active, over } = event
      if (!over || active.id === over.id || !groceryList) return

      const oldIndex = groupItems.findIndex((i) => i.id === active.id)
      const newIndex = groupItems.findIndex((i) => i.id === over.id)
      if (oldIndex === -1 || newIndex === -1) return

      const reordered = arrayMove(groupItems, oldIndex, newIndex)
      const items = reordered.map((item, idx) => ({ id: item.id, position: idx }))

      // Optimistic update
      setGroceryList((prev) => {
        if (!prev) return prev
        const positionMap = new Map(items.map((i) => [i.id, i.position]))
        return {
          ...prev,
          items: prev.items.map((item) =>
            positionMap.has(item.id) ? { ...item, position: positionMap.get(item.id)! } : item,
          ),
        }
      })

      try {
        const token = localStorage.getItem('token')
        await fetch(`${entrypoint}/grocery/reorder`, {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({ items }),
        })
      } catch {
        notify('Erreur', { type: 'error' })
        fetchList()
      }
    },
    [groceryList, notify, fetchList],
  )

  // Track item additions/removals for animations
  const { addedIds, removingItems } = useItemTransitions(
    groceryList?.items ?? [],
    useCallback((item: GroceryItem) => item.id, []),
  )
  const removingIds = useMemo(() => new Set(removingItems.map((i) => i.id)), [removingItems])

  const withPendingQuantities = useCallback(
    (items: GroceryItem[]) =>
      items.map((item) =>
        pendingQuantities[item.id] !== undefined ? { ...item, quantity: pendingQuantities[item.id] } : item,
      ),
    [pendingQuantities],
  )

  // A line with a future `buyAfter` is not today's shopping: it waits in « Plus tard ».
  const { todayItems, laterItems } = useMemo(() => {
    const today = localToday()
    const all = withPendingQuantities(groceryList?.items ?? [])
    return {
      todayItems: all.filter((i) => !isDeferred(i, today)),
      laterItems: all
        .filter((i) => isDeferred(i, today))
        .sort((a, b) => (a.buyAfter ?? '').localeCompare(b.buyAfter ?? '') || a.label.localeCompare(b.label)),
    }
  }, [groceryList?.items, withPendingQuantities])

  // Merge current items + ghost (removing) items for display
  const displayItems = useMemo(() => {
    const currentIds = new Set(todayItems.map((i) => i.id))
    const ghosts = removingItems.filter((ri) => !currentIds.has(ri.id))
    return [...todayItems, ...ghosts]
  }, [todayItems, removingItems])

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
    // Sort items within each group by position
    for (const group of Object.values(groupMap)) {
      group.items.sort((a, b) => a.position - b.position)
    }
    storeGroups.push(...Object.values(groupMap).sort((a, b) => a.visitOrder - b.visitOrder))
  }

  // Counts exclude ghost (removing) items
  const checkedCount = todayItems.filter((i) => i.checked).length
  const totalCount = todayItems.length

  return (
    <Box sx={{ p: { xs: 1, md: 2 }, maxWidth: 800, mx: 'auto', width: '100%' }}>
      <Title title="Courses" />

      <Paper sx={{ p: { xs: 1, md: 2 } }}>
        {loading && !groceryList && (
          <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
            <CircularProgress />
          </Box>
        )}

        {groceryList && (
          <>
            {/* Wraps rather than overflows: the title and the two actions are
                320px of content on a 393px screen once the padding is paid. */}
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
                gap: 1,
                mb: 2,
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
                <ShoppingCartIcon color="primary" />
                <Typography variant="h6" sx={{ fontSize: { xs: '1rem', md: '1.25rem' } }}>
                  Ma liste de courses
                </Typography>
                <Chip
                  label={`${checkedCount}/${totalCount}`}
                  size="small"
                  color={checkedCount === totalCount && totalCount > 0 ? 'success' : 'default'}
                />
              </Box>
              <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
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
                <Box key={storeKey} data-testid="grocery-store-group" data-store={group.storeName} sx={{ mb: 1 }}>
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
                      <DndContext
                        sensors={sensors}
                        collisionDetection={closestCenter}
                        onDragEnd={(event) => handleDragEnd(event, group.items)}
                      >
                        <SortableContext
                          items={group.items.map((i) => i.id)}
                          strategy={verticalListSortingStrategy}
                        >
                          {group.items.map((item) => (
                            <SortableGroceryItem
                              key={item.id}
                              item={item}
                              addedIds={addedIds}
                              removingIds={removingIds}
                              onCheck={handleCheck}
                              onViewDetail={setDetailItem}
                              onQuantityChange={handleQuantityChange}
                              onQuantityInvalid={handleQuantityInvalid}
                              isDragDisabled={removingIds.size > 0 || addedIds.size > 0}
                            />
                          ))}
                        </SortableContext>
                      </DndContext>
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

            {laterItems.length > 0 && (
              <Box data-testid="grocery-later" sx={{ mt: 1 }}>
                <List dense>
                  <ListItemButton onClick={() => setLaterOpen((open) => !open)} dense>
                    <ListItemText
                      primary={`Plus tard (${laterItems.length})`}
                      primaryTypographyProps={{ fontWeight: 600 }}
                    />
                    {laterOpen ? <ExpandLess /> : <ExpandMore />}
                  </ListItemButton>
                  <Collapse in={laterOpen} unmountOnExit>
                    {laterItems.map((item) => (
                      <ListItem key={item.id} data-testid="grocery-later-item">
                        <ListItemText
                          primary={item.label}
                          secondary={`À acheter à partir du ${formatBuyAfter(item.buyAfter ?? '')}`}
                        />
                      </ListItem>
                    ))}
                  </Collapse>
                </List>
              </Box>
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
                      const id = bareId(ps)
                      const store = stores.find((s) => bareId(s.id) === id)
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
                <MenuItem value="jar">bocal</MenuItem>
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

      {/* Item edit dialog */}
      <Dialog open={detailItem !== null} onClose={() => setDetailItem(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Modifier l&apos;article</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
            <Autocomplete
              freeSolo
              options={filteredEditProducts}
              getOptionLabel={(option) => (typeof option === 'string' ? option : option.name)}
              inputValue={editLabel}
              onInputChange={(_e, value) => setEditLabel(value)}
              onChange={(_e, value) => {
                if (value && typeof value !== 'string') {
                  setEditLabel(value.name)
                  if (value.defaultUnit) setEditUnit(value.defaultUnit)
                  if (value.category) setEditCategory(value.category)
                  if (value.preferredStore) {
                    const ps = value.preferredStore
                    if (typeof ps === 'object' && ps.id) {
                      setEditSelectedStore(ps)
                      setEditStoreInput(ps.name)
                    } else if (typeof ps === 'string') {
                      const id = bareId(ps)
                      const store = stores.find((s) => bareId(s.id) === id)
                      if (store) {
                        setEditSelectedStore(store)
                        setEditStoreInput(store.name)
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
              noOptionsText={editLabel.length < 2 ? 'Tapez au moins 2 caractères' : 'Nouveau produit'}
            />
            <TextField
              label="Quantité"
              type="number"
              value={editQuantity}
              onChange={(e) => setEditQuantity(e.target.value)}
            />
            <FormControl>
              <InputLabel>Unité</InputLabel>
              <Select value={editUnit} onChange={(e) => setEditUnit(e.target.value)} label="Unité">
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
                <MenuItem value="jar">bocal</MenuItem>
              </Select>
            </FormControl>
            <FormControl>
              <InputLabel>Catégorie</InputLabel>
              <Select
                value={editCategory}
                onChange={(e) => setEditCategory(e.target.value)}
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
              value={editSelectedStore}
              inputValue={editStoreInput}
              onInputChange={(_e, value) => setEditStoreInput(value)}
              onChange={(_e, value) => {
                if (value && typeof value !== 'string') {
                  setEditSelectedStore(value)
                } else {
                  setEditSelectedStore(null)
                }
              }}
              renderInput={(params) => <TextField {...params} label="Magasin" />}
              noOptionsText="Nouveau magasin"
              isOptionEqualToValue={(option, value) => option.id === value.id}
            />
            <TextField
              label="Source"
              value={
                detailItem?.source === 'recipe'
                  ? 'Recette'
                  : detailItem?.source === 'recurring'
                    ? 'Récurrent'
                    : 'Manuel'
              }
              disabled
              fullWidth
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDetailItem(null)}>Annuler</Button>
          <Button onClick={handleEditItem} variant="contained" disabled={!editLabel}>
            Enregistrer
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
