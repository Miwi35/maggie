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
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import MoreVertIcon from '@mui/icons-material/MoreVert'
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
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const renameOnClose = useRef(false)

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
      sx={{
        gap: 0.5,
        minHeight: 52,
        borderRadius: '12px',
        bgcolor: 'action.hover',
        mb: 0.5,
        pr: 0.5,
        cursor: 'grab',
        '&:hover .aisle-menu, & .aisle-menu:focus-visible': { opacity: 1 },
      }}
    >
      <IconButton
        size="small"
        aria-label={`Déplacer ${aisle.name}`}
        sx={{ cursor: 'grab' }}
        {...attributes}
        {...listeners}
      >
        <DragIndicatorIcon fontSize="small" sx={{ color: 'text.disabled' }} />
      </IconButton>
      <Typography
        data-testid="store-aisle-rank"
        sx={(theme) => ({ width: 18, fontSize: 13, fontWeight: 500, color: theme.palette.module?.courses })}
      >
        {rank}
      </Typography>
      {draft === null ? (
        <Typography
          data-testid="store-aisle-name"
          onDoubleClick={() => setDraft(aisle.name)}
          sx={{ flex: 1, minWidth: 0, fontSize: 15, overflowWrap: 'anywhere' }}
        >
          {aisle.name}
        </Typography>
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
        <Typography data-testid="store-aisle-count" sx={{ fontSize: 13, color: 'text.secondary', whiteSpace: 'nowrap' }}>
          {`${count} produit${count > 1 ? 's' : ''}`}
        </Typography>
      )}
      <IconButton
        className="aisle-menu"
        size="small"
        aria-label={`Options du rayon ${aisle.name}`}
        onClick={(e) => setAnchor(e.currentTarget)}
        sx={{ opacity: 0 }}
      >
        <MoreVertIcon fontSize="small" />
      </IconButton>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        disableRestoreFocus
        slotProps={{
          transition: {
            // The field takes the focus once the menu has let go of it, or its blur would end the edit.
            onExited: () => {
              if (!renameOnClose.current) return
              renameOnClose.current = false
              setDraft(aisle.name)
            },
          },
        }}
      >
        <MenuItem
          onClick={() => {
            renameOnClose.current = true
            setAnchor(null)
          }}
        >
          Renommer
        </MenuItem>
        <MenuItem
          onClick={() => {
            setAnchor(null)
            onDelete(aisle)
          }}
        >
          Supprimer
        </MenuItem>
      </Menu>
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
  const [adding, setAdding] = useState(false)
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
  useEffect(() => {
    aislesRef.current = aisles
  }, [aisles])

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
      setAdding(false)
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
      {adding ? (
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', mt: 1 }}>
          <TextField
            size="small"
            autoFocus
            label="Nouveau rayon"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                handleAdd()
              } else if (e.key === 'Escape') {
                setNewName('')
                setAdding(false)
              }
            }}
            sx={{ flex: 1 }}
          />
          <Button onClick={handleAdd} disabled={!newName.trim()}>
            Ajouter
          </Button>
        </Box>
      ) : (
        <Button
          fullWidth
          onClick={() => setAdding(true)}
          sx={(theme) => ({
            mt: 1,
            height: 44,
            borderRadius: '12px',
            boxShadow: 'inset 0 0 0 1px rgba(255,255,255,0.12)',
            color: theme.palette.module?.courses,
          })}
        >
          + Rayon
        </Button>
      )}

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
