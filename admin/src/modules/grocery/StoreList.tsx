import { useRef, useState } from 'react'
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
import ButtonBase from '@mui/material/ButtonBase'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import MoreVertIcon from '@mui/icons-material/MoreVert'
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

const StoreName = ({ store, onDone }: { store: StoreRecord; onDone: () => void }) => {
  const notify = useNotify()
  const [update] = useUpdate()
  const [draft, setDraft] = useState<string | null>(null)

  const commit = () => {
    if (draft === null) return
    const name = draft.trim()
    setDraft(null)
    onDone()
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
      onDone()
    }
  }

  return (
    <TextField
      autoFocus
      variant="standard"
      value={draft ?? store.name}
      onChange={(e) => setDraft(e.target.value)}
      onKeyDown={onKeyDown}
      onBlur={commit}
      slotProps={{
        htmlInput: { 'aria-label': 'Nom du magasin' },
        input: { sx: { fontSize: 18, fontWeight: 500 } },
      }}
      sx={{ flex: 1 }}
    />
  )
}

const StoreDetail = ({ store, onDeleted }: { store: StoreRecord; onDeleted: () => void }) => {
  const notify = useNotify()
  const [deleteOne] = useDelete()
  const [confirming, setConfirming] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const renameOnClose = useRef(false)

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
    <Box data-testid="store-detail" sx={{ minWidth: 0, bgcolor: 'background.paper', borderRadius: '20px', p: '18px' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
        {renaming ? (
          <StoreName store={store} onDone={() => setRenaming(false)} />
        ) : (
          <Typography component="h2" sx={{ flex: 1, minWidth: 0, fontSize: 18, fontWeight: 500, overflowWrap: 'anywhere' }}>
            {store.name} · ordre du parcours
          </Typography>
        )}
        <IconButton aria-label="Options du magasin" size="small" onClick={(e) => setAnchor(e.currentTarget)}>
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
                setRenaming(true)
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
          <MenuItem component={Link} to={`/stores/${encodeURIComponent(store.id)}`} onClick={() => setAnchor(null)}>
            Détails
          </MenuItem>
          <MenuItem
            onClick={() => {
              setAnchor(null)
              setConfirming(true)
            }}
          >
            Supprimer
          </MenuItem>
        </Menu>
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
    <Box data-testid="stores-board" sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h5" component="h1" sx={{ fontWeight: 500, fontSize: 26 }}>
          Magasins
        </Typography>
        <Button
          variant="contained"
          onClick={() => setAdding(true)}
          sx={{ flexShrink: 0, height: 40, borderRadius: '10px', bgcolor: 'module.courses', color: 'background.default', boxShadow: 'none' }}
        >
          + Magasin
        </Button>
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '320px minmax(0, 560px)' }, gap: 2, alignItems: 'start' }}>
        <Box sx={{ bgcolor: 'background.paper', borderRadius: '20px', p: 1 }}>
          {stores.map((store) => {
            const active = store === selected
            return (
              <ButtonBase
                key={store.id}
                data-testid="store-row"
                data-store={store.name}
                aria-current={active || undefined}
                onClick={() => setSelectedId(String(store.id))}
                sx={(theme) => ({
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'flex-start',
                  justifyContent: 'center',
                  width: '100%',
                  height: 60,
                  px: 2,
                  borderRadius: '12px',
                  textAlign: 'left',
                  bgcolor: active ? 'rgba(143,184,255,0.16)' : 'transparent',
                  color: active ? theme.palette.module?.courses : 'text.primary',
                })}
              >
                <Typography component="span" sx={{ fontSize: 15, fontWeight: active ? 500 : 400, color: 'inherit' }}>
                  {store.name}
                </Typography>
                <Typography component="span" sx={{ fontSize: 12, color: 'text.secondary' }}>
                  {countsLabel(counts[bareId(String(store.id))])}
                </Typography>
              </ButtonBase>
            )
          })}
          {stores.length === 0 && <Typography color="text.secondary" sx={{ p: 2 }}>Aucun magasin pour l'instant</Typography>}
        </Box>
      {selected && <StoreDetail key={selected.id} store={selected} onDeleted={() => setSelectedId(null)} />}
      </Box>
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
