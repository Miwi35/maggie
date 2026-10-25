import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import {
  Confirm,
  CreateBase,
  EditBase,
  SaveButton,
  Toolbar,
  useDelete,
  useEditContext,
  useNotify,
  useRecordContext,
} from 'react-admin'
import { ProductForm } from './ProductForm'

/** What the panel on the right shows: a product to edit, a blank one, or nothing. */
export type PanelTarget = { kind: 'edit'; id: string } | { kind: 'create' } | null

interface PanelProps {
  target: PanelTarget
  onClose: () => void
}

const CLOSE_AFTER = { edit: 'Produit enregistré', create: 'Produit créé' } as const

const PanelFrame = ({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) => (
  <Box role="dialog" aria-label={title} data-testid="product-panel" sx={{ display: 'flex', flexDirection: 'column', minHeight: '100%' }}>
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2.5, pt: 2 }}>
      <Typography variant="h6" component="h2" sx={{ flex: 1, fontSize: '1.15rem' }}>
        {title}
      </Typography>
      <IconButton size="small" aria-label="Fermer" onClick={onClose}>
        <CloseIcon fontSize="small" />
      </IconButton>
    </Box>
    {children}
  </Box>
)

const DeleteInPanel = ({ onDeleted }: { onDeleted: () => void }) => {
  const record = useRecordContext<{ id: string; name: string }>()
  const notify = useNotify()
  const [open, setOpen] = useState(false)
  const [deleteOne, { isPending }] = useDelete()

  if (!record) return null

  const confirm = () =>
    deleteOne(
      'products',
      { id: record.id, previousData: record },
      {
        mutationMode: 'pessimistic',
        onSuccess: () => {
          setOpen(false)
          notify('Produit supprimé', { type: 'success' })
          onDeleted()
        },
        onError: (error) => {
          setOpen(false)
          notify(`Erreur : ${(error as Error).message}`, { type: 'error' })
        },
      },
    )

  return (
    <>
      <Button color="error" onClick={() => setOpen(true)} sx={{ ml: 'auto' }}>
        Supprimer
      </Button>
      <Confirm
        isOpen={open}
        loading={isPending}
        title="Supprimer ce produit ?"
        content={`« ${record.name} » sera retiré de vos produits. Cette action est définitive.`}
        confirm="Supprimer"
        cancel="Annuler"
        confirmColor="warning"
        onConfirm={confirm}
        onClose={() => setOpen(false)}
      />
    </>
  )
}

const PanelToolbar = ({ onDeleted }: { onDeleted?: () => void }) => (
  <Toolbar sx={{ display: 'flex', gap: 1 }}>
    <SaveButton label="Enregistrer" />
    {onDeleted && <DeleteInPanel onDeleted={onDeleted} />}
  </Toolbar>
)

const EditSheet = ({ onClose }: { onClose: () => void }) => {
  const { record, isPending } = useEditContext<{ id: string; name: string }>()

  return (
    <PanelFrame title={record?.name ?? 'Produit'} onClose={onClose}>
      {isPending ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
          <CircularProgress size={28} />
        </Box>
      ) : (
        <ProductForm toolbar={<PanelToolbar onDeleted={onClose} />} />
      )}
    </PanelFrame>
  )
}

const PanelContent = ({ target, onClose }: { target: Exclude<PanelTarget, null>; onClose: () => void }) => {
  const notify = useNotify()
  const done = (kind: 'edit' | 'create') => () => {
    notify(CLOSE_AFTER[kind], { type: 'success' })
    onClose()
  }

  if (target.kind === 'create') {
    return (
      <CreateBase resource="products" redirect={false} mutationOptions={{ onSuccess: done('create') }}>
        <PanelFrame title="Nouveau produit" onClose={onClose}>
          <ProductForm toolbar={<PanelToolbar />} />
        </PanelFrame>
      </CreateBase>
    )
  }

  return (
    <EditBase
      key={target.id}
      resource="products"
      id={target.id}
      redirect={false}
      mutationMode="pessimistic"
      mutationOptions={{ onSuccess: done('edit') }}
    >
      <EditSheet onClose={onClose} />
    </EditBase>
  )
}

/** The fiche of a product, in a panel on the right over the list: editing, creating and deleting all happen here. */
export const ProductPanel = ({ target, onClose }: PanelProps) => (
  <Drawer
    anchor="right"
    open={target !== null}
    onClose={onClose}
    slotProps={{ paper: { sx: { width: { xs: '100%', sm: 560 }, maxWidth: '100%' } } }}
  >
    {target && <PanelContent target={target} onClose={onClose} />}
  </Drawer>
)
