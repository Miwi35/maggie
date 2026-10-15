import { useCallback, useEffect, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useNotify, useRecordContext } from 'react-admin'
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
import AddIcon from '@mui/icons-material/Add'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import { FormSection } from '../../components/form/FormSection'
import { useMercure } from '../../hooks/useMercure'
import { AISLE_TOPIC, AisleError, createAisle, deleteAisle, fetchAisles, updateAisle } from './aislesApi'
import type { Aisle } from './aislesApi'
import { bareId } from './recurring'

const AISLE_TOPICS = [AISLE_TOPIC]

const errorText = (error: unknown, fallback: string) => (error instanceof AisleError ? error.message : fallback)

function SortableAisle({
  aisle,
  onRename,
  onDelete,
}: {
  aisle: Aisle
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
      sx={{ gap: 0.5 }}
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
      {draft === null ? (
        <ListItemText primary={aisle.name} onDoubleClick={() => setDraft(aisle.name)} />
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
 * The aisles of the store open in the form, in the order of the way through
 * it: drag one to move it, and the grocery list follows (MAG-493).
 */
export const StoreAisles = () => {
  const record = useRecordContext<{ id: string }>()
  const storeId = record?.id ? String(record.id) : null
  const notify = useNotify()
  const [aisles, setAisles] = useState<Aisle[]>([])
  const [newName, setNewName] = useState('')
  const [toDelete, setToDelete] = useState<Aisle | null>(null)

  const reload = useCallback(async () => {
    if (!storeId) return
    try {
      setAisles(await fetchAisles(storeId))
    } catch {
      notify("Erreur : les rayons n'ont pas été chargés", { type: 'error' })
    }
  }, [storeId, notify])

  useEffect(() => {
    reload()
  }, [reload])

  useMercure(
    AISLE_TOPICS,
    useCallback(
      (data?: string) => {
        try {
          const payload = data ? JSON.parse(data) : null
          // Another store's aisle: nothing to redraw here. A deletion carries no store.
          if (payload?.storeId && storeId && bareId(String(payload.storeId)) !== bareId(storeId)) return
        } catch {
          // Unreadable: read the aisles again.
        }
        reload()
      },
      [reload, storeId],
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
    if (!storeId || !name) return
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

  if (!storeId) return null

  return (
    <Box data-testid="store-aisles" sx={{ px: 2, pb: 2, maxWidth: 680 }}>
      <FormSection title="Rayons" />
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={aisles.map((a) => a.id)} strategy={verticalListSortingStrategy}>
          <List dense disablePadding>
            {aisles.map((aisle) => (
              <SortableAisle key={aisle.id} aisle={aisle} onRename={handleRename} onDelete={setToDelete} />
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
        <Button startIcon={<AddIcon />} onClick={handleAdd} disabled={!newName.trim()}>
          Ajouter
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
