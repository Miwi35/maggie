import { useState } from 'react'
import type { KeyboardEvent } from 'react'
import { Link } from 'react-router-dom'
import { useCreate, useDelete, useListContext, useNotify, useUpdate } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { STORES_PAGE_TOPICS } from './aislesApi'
import { StoreAisles } from './StoreAisles'
import { useStoreCounts } from './useStoreCounts'
import type { StoreCount } from './useStoreCounts'
import { bareId } from './recurring'

const STORE_LIVE: LiveConfig = {
  topics: STORES_PAGE_TOPICS,
  required: ['name', 'visitOrder'],
  accept: (row) => row.id.startsWith('/api/stores/'),
}

interface StoreRecord {
  id: string
  name: string
  visitOrder?: number | null
}

const plural = (n: number, one: string, many: string) => `${n} ${n > 1 ? many : one}`

const countsLabel = (count?: StoreCount) =>
  count ? `${plural(count.aisles, 'rayon', 'rayons')} · ${plural(count.products, 'produit', 'produits')}` : ' '

const byTour = (a: StoreRecord, b: StoreRecord) =>
  (a.visitOrder ?? 0) - (b.visitOrder ?? 0) || a.name.localeCompare(b.name, 'fr')

const StoreName = ({ store }: { store: StoreRecord }) => {
  const notify = useNotify()
  const [update] = useUpdate()
  const [draft, setDraft] = useState<string | null>(null)

  const commit = () => {
    if (draft === null) return
    const name = draft.trim()
    setDraft(null)
    if (!name || name === store.name) return
    update(
      'stores',
      { id: store.id, data: { name }, previousData: store },
      { onError: () => notify("Erreur : le magasin n'a pas été renommé", { type: 'error' }) },
    )
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
    <TextField
      variant="standard"
      value={draft ?? store.name}
      onChange={(e) => setDraft(e.target.value)}
      onKeyDown={onKeyDown}
      onBlur={commit}
      slotProps={{
        htmlInput: { 'aria-label': 'Nom du magasin' },
        input: { sx: { fontSize: '1.5rem', fontWeight: 600 } },
      }}
      sx={{ flex: 1 }}
    />
  )
}

const StoreDetail = ({ store, onDeleted }: { store: StoreRecord; onDeleted: () => void }) => {
  const notify = useNotify()
  const [deleteOne] = useDelete()
  const [confirming, setConfirming] = useState(false)

  const remove = () => {
    setConfirming(false)
    deleteOne(
      'stores',
      { id: store.id, previousData: store },
      {
        onSuccess: onDeleted,
        onError: () => notify(`Erreur : le magasin « ${store.name} » n'a pas été supprimé`, { type: 'error' }),
      },
    )
  }

  return (
    <Box data-testid="store-detail" sx={{ minWidth: 0 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 3 }}>
        <StoreName store={store} />
        <Button component={Link} to={`/stores/${encodeURIComponent(store.id)}`} size="small">
          Détails
        </Button>
        <IconButton aria-label={`Supprimer ${store.name}`} onClick={() => setConfirming(true)}>
          <DeleteOutlineIcon />
        </IconButton>
      </Box>
      <StoreAisles storeId={bareId(store.id)} />

      <Dialog open={confirming} onClose={() => setConfirming(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Supprimer le magasin « {store.name} » ?</DialogTitle>
        <DialogActions>
          <Button onClick={() => setConfirming(false)}>Annuler</Button>
          <Button color="error" variant="contained" onClick={remove}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

const NewStore = ({ open, size, onClose, onCreated }: { open: boolean; size: number; onClose: () => void; onCreated: (id: string) => void }) => {
  const notify = useNotify()
  const [create, { isPending }] = useCreate()
  const [name, setName] = useState('')

  const submit = () => {
    const trimmed = name.trim()
    if (!trimmed) return
    create(
      'stores',
      { data: { name: trimmed, visitOrder: size } },
      {
        onSuccess: (created) => {
          setName('')
          onCreated(String(created.id))
        },
        onError: () => notify(`Erreur : le magasin « ${trimmed} » n'a pas été ajouté`, { type: 'error' }),
      },
    )
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Nouveau magasin</DialogTitle>
      <DialogContent>
        <TextField
          autoFocus
          fullWidth
          margin="dense"
          label="Nom"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              submit()
            }
          }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button variant="contained" onClick={submit} disabled={!name.trim() || isPending}>
          Ajouter
        </Button>
      </DialogActions>
    </Dialog>
  )
}

const StoresBoard = () => {
  const { data, isPending } = useListContext<StoreRecord>()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const stores = [...(data ?? [])].sort(byTour)
  const counts = useStoreCounts(stores.map((s) => String(s.id)))

  if (isPending) return null

  const selected = stores.find((s) => String(s.id) === selectedId) ?? stores[0]

  return (
    <Box
      data-testid="stores-board"
      sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'minmax(260px, 340px) 1fr' }, gap: 4, py: 2 }}
    >
      <Box>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
          <Typography variant="subtitle1" component="h2" sx={{ fontWeight: 600 }}>
            Magasins
          </Typography>
          <Button onClick={() => setAdding(true)}>+ Magasin</Button>
        </Box>
        <List disablePadding>
          {stores.map((store) => (
            <ListItemButton
              key={store.id}
              data-testid="store-row"
              data-store={store.name}
              selected={store === selected}
              onClick={() => setSelectedId(String(store.id))}
              sx={{ borderRadius: 1 }}
            >
              <ListItemText primary={store.name} secondary={countsLabel(counts[bareId(String(store.id))])} />
            </ListItemButton>
          ))}
        </List>
        {stores.length === 0 && <Typography color="text.secondary">Aucun magasin pour l'instant</Typography>}
      </Box>
      {selected && <StoreDetail key={selected.id} store={selected} onDeleted={() => setSelectedId(null)} />}
      <NewStore
        open={adding}
        size={stores.length}
        onClose={() => setAdding(false)}
        onCreated={(id) => {
          setAdding(false)
          setSelectedId(id)
        }}
      />
    </Box>
  )
}

export const StoreList = () => (
  <LiveList live={STORE_LIVE} actions={false} pagination={false} empty={false} perPage={100} sort={{ field: 'name', order: 'ASC' }}>
    <StoresBoard />
  </LiveList>
)
