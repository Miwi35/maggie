import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useNotify } from 'react-admin'
import { DndContext, closestCenter, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import { useMercure } from '../../hooks/useMercure'
import {
  STORES_PAGE_TOPICS,
  AisleError,
  createAisle,
  deleteAisle,
  fetchAisleProductCount,
  fetchAisles,
  updateAisle,
} from './aislesApi'
import type { Aisle } from './aislesApi'
import { bareId } from './recurring'

// How long a burst of Mercure messages is waited out before the aisles are read again.
const BURST_MS = 300

// Products are counted from the search index, which the worker updates after the event left.
const INDEX_DELAY_MS = 3000

const errorText = (error: unknown, fallback: string) => (error instanceof AisleError ? error.message : fallback)

function SortableAisle({
  aisle,
  rank,
  count,
  onRename,
  onDelete,
}: {
  aisle: Aisle
  rank: number
  count?: number
  onRename: (aisle: Aisle, name: string) => void
  onDelete: (aisle: Aisle) => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: aisle.id })
  const [draft, setDraft] = useState<string | null>(null)

  const commit = () => {
    if (draft === null) return
    const name = draft.trim()
    setDraft(null)
    if (name && name !== aisle.name) onRename(aisle, name)
  }

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Enter') {
      event.preventDefault()
      commit()
    } else if (event.key === 'Escape') {
      setDraft(null)
    }
  }

  return (
    <ListItem
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.5 : undefined }}
      data-testid="store-aisle"
      data-aisle={aisle.name}
      disablePadding
      sx={{ gap: 0.5, minHeight: 52, borderRadius: '12px', bgcolor: 'action.hover', mb: 0.5, pr: 0.5 }}
    >
      <IconButton
        size="small"
        aria-label={`Déplacer ${aisle.name}`}
        sx={{ cursor: 'grab' }}
        {...attributes}
        {...listeners}
      >
        <DragIndicatorIcon fontSize="small" sx={{ color: 'action.disabled' }} />
      </IconButton>
      <Typography
        data-testid="store-aisle-rank"
        sx={(theme) => ({ minWidth: 20, fontWeight: 600, color: theme.palette.module?.cuisine })}
      >
        {rank}
      </Typography>
      {draft === null ? (
        <ListItemText
          primary={aisle.name}
          slotProps={{ primary: { 'data-testid': 'store-aisle-name' } as object }}
          onDoubleClick={() => setDraft(aisle.name)}
        />
      ) : (
        <TextField
          size="small"
          autoFocus
          fullWidth
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKeyDown}
          onBlur={commit}
          slotProps={{ htmlInput: { 'aria-label': `Nom du rayon ${aisle.name}` } }}
        />
      )}
      {count !== undefined && (
        <Typography data-testid="store-aisle-count" variant="body2" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
          {`${count} produit${count > 1 ? 's' : ''}`}
        </Typography>
      )}
      <IconButton size="small" aria-label={`Renommer ${aisle.name}`} onClick={() => setDraft(aisle.name)}>
        <EditOutlinedIcon fontSize="small" />
      </IconButton>
      <IconButton size="small" aria-label={`Supprimer ${aisle.name}`} onClick={() => onDelete(aisle)}>
        <DeleteOutlineIcon fontSize="small" />
      </IconButton>
    </ListItem>
  )
}

/**
 * The aisles of a store, in the order of the way through it: drag one to move
 * it, and the grocery list follows (MAG-493).
 */
export const StoreAisles = ({ storeId }: { storeId: string }) => {
  const notify = useNotify()
  const [aisles, setAisles] = useState<Aisle[]>([])
  const [newName, setNewName] = useState('')
  const [toDelete, setToDelete] = useState<Aisle | null>(null)
  const [counts, setCounts] = useState<Record<string, number>>({})

  // Only the last read answers: an older one still under way is cut, and neither its result nor its failure counts.
  const reading = useRef<AbortController | null>(null)

  const cancelReading = useCallback(() => {
    reading.current?.abort()
    reading.current = null
  }, [])

  // A count that cannot be read keeps its last value: the page does not depend on it.
  const readCounts = useCallback(
    async (list: Aisle[], signal: AbortSignal) => {
      const answers = await Promise.allSettled(list.map((a) => fetchAisleProductCount(storeId, a.id, signal)))
      if (signal.aborted) return
      const read: Record<string, number> = {}
      answers.forEach((answer, i) => {
        if (answer.status === 'fulfilled') read[list[i].id] = answer.value
      })
      setCounts((previous) => ({ ...previous, ...read }))
    },
    [storeId],
  )

  const reload = useCallback(async () => {
    cancelReading()
    const own = new AbortController()
    reading.current = own
    try {
      const read = await fetchAisles(storeId, own.signal)
      if (own.signal.aborted) return
      setAisles(read)
      await readCounts(read, own.signal)
    } catch {
      if (!own.signal.aborted) notify("Erreur : les rayons n'ont pas été chargés", { type: 'error' })
    }
  }, [storeId, notify, cancelReading, readCounts])

  // A move renumbers the store's aisles and each publishes its update: one read once the burst is over.
  const burst = useRef<ReturnType<typeof setTimeout>>(undefined)
  // The index catches up after the event: the counts alone are read again then, the aisles are not.
  const indexed = useRef<ReturnType<typeof setTimeout>>(undefined)
  const aislesRef = useRef<Aisle[]>([])
  aislesRef.current = aisles

  useEffect(() => {
    reload()
    return () => {
      clearTimeout(burst.current) // a burst of the store left must not read in the one shown now
      clearTimeout(indexed.current)
      cancelReading()
    }
  }, [reload, cancelReading])

  useMercure(
    STORES_PAGE_TOPICS,
    useCallback(
      (data?: string) => {
        try {
          const payload = data ? JSON.parse(data) : null
          // Another store's aisle: nothing to redraw here. A deletion carries no store.
          if (payload?.storeId && bareId(String(payload.storeId)) !== bareId(storeId)) return
        } catch {
          // Unreadable: read the aisles again.
        }
        clearTimeout(burst.current)
        burst.current = setTimeout(reload, BURST_MS)
        clearTimeout(indexed.current)
        indexed.current = setTimeout(() => {
          const own = new AbortController()
          reading.current = own
          readCounts(aislesRef.current, own.signal)
        }, INDEX_DELAY_MS)
      },
      [reload, storeId, readCounts],
    ),
  )

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const handleDragEnd = async ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return
    const oldIndex = aisles.findIndex((a) => a.id === active.id)
    const newIndex = aisles.findIndex((a) => a.id === over.id)
    if (oldIndex === -1 || newIndex === -1) return

    clearTimeout(burst.current) // a read begun before the move would bring back the old order
    cancelReading()
    setAisles(arrayMove(aisles, oldIndex, newIndex).map((a, i) => ({ ...a, position: i + 1 })))
    try {
      await updateAisle(String(active.id), { position: newIndex + 1 })
    } catch (error) {
      notify(errorText(error, "Erreur : le rayon n'a pas été déplacé"), { type: 'error' })
      reload()
    }
  }

  const handleAdd = async () => {
    const name = newName.trim()
    if (!name) return
    try {
      const created = await createAisle(storeId, name)
      setNewName('')
      setAisles((prev) => (prev.some((a) => a.id === created.id) ? prev : [...prev, created]))
    } catch (error) {
      notify(errorText(error, `Erreur : le rayon « ${name} » n'a pas été ajouté`), { type: 'error' })
    }
  }

  const handleRename = async (aisle: Aisle, name: string) => {
    setAisles((prev) => prev.map((a) => (a.id === aisle.id ? { ...a, name } : a)))
    try {
      await updateAisle(aisle.id, { name })
    } catch (error) {
      notify(errorText(error, "Erreur : le rayon n'a pas été renommé"), { type: 'error' })
      reload()
    }
  }

  const handleDelete = async (aisle: Aisle) => {
    setToDelete(null)
    setAisles((prev) => prev.filter((a) => a.id !== aisle.id))
    try {
      await deleteAisle(aisle.id)
    } catch (error) {
      notify(errorText(error, `Erreur : le rayon « ${aisle.name} » n'a pas été supprimé`), { type: 'error' })
      reload()
    }
  }

  return (
    <Box data-testid="store-aisles">
      <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 600, mb: 1 }}>
        Ordre du parcours
      </Typography>
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={aisles.map((a) => a.id)} strategy={verticalListSortingStrategy}>
          <List dense disablePadding>
            {aisles.map((aisle, index) => (
              <SortableAisle
                key={aisle.id}
                aisle={aisle}
                rank={index + 1}
                count={counts[aisle.id]}
                onRename={handleRename}
                onDelete={setToDelete}
              />
            ))}
          </List>
        </SortableContext>
      </DndContext>
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', mt: 1 }}>
        <TextField
          size="small"
          label="Nouveau rayon"
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              handleAdd()
            }
          }}
          sx={{ flex: 1 }}
        />
        <Button onClick={handleAdd} disabled={!newName.trim()}>
          + Rayon
        </Button>
      </Box>

      <Dialog open={toDelete !== null} onClose={() => setToDelete(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Supprimer le rayon « {toDelete?.name} » ?</DialogTitle>
        <DialogActions>
          <Button onClick={() => setToDelete(null)}>Annuler</Button>
          <Button color="error" variant="contained" onClick={() => toDelete && handleDelete(toDelete)}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
