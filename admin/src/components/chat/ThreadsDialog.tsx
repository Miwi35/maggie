import { useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogContentText from '@mui/material/DialogContentText'
import DialogTitle from '@mui/material/DialogTitle'
import Typography from '@mui/material/Typography'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import { ContextList } from '../mind/ContextList'
import type { ContextState } from '../mind/types'

interface ThreadsDialogProps {
  open: boolean
  onClose: () => void
  contexts: ContextState[]
  /** Called once the owner has confirmed; the deferred deletion itself is the chat's. */
  onDelete: (context: ContextState) => void
}

/** What the confirmation says goes with the thread — « Ce fil et ses 12 messages seront supprimés ». */
function deletionMessage(count: number | undefined): string {
  if (count === undefined) return 'Ce fil et ses messages seront supprimés'
  if (count === 0) return 'Ce fil sera supprimé'
  if (count === 1) return 'Ce fil et 1 message seront supprimés'
  return `Ce fil et ses ${count} messages seront supprimés`
}

/**
 * The threads (« fils ») Maggie files the conversation into, opened from the chat
 * header (MAG-342). The conversation is one; this is where the owner consults and
 * cleans its filing. Full screen under `md`, where the chat is itself a sheet.
 *
 * Deleting takes the thread's messages with it, so it asks first and says how many.
 */
export const ThreadsDialog = ({ open, onClose, contexts, onDelete }: ThreadsDialogProps) => {
  const isNarrow = useNarrowScreen()
  const [target, setTarget] = useState<ContextState | null>(null)
  const [confirming, setConfirming] = useState(false)

  const ask = (context: ContextState) => {
    setTarget(context)
    setConfirming(true)
  }

  const confirm = () => {
    setConfirming(false)
    if (target) onDelete(target)
  }

  return (
    <>
      <Dialog
        open={open}
        onClose={onClose}
        fullScreen={isNarrow}
        fullWidth
        maxWidth="sm"
        aria-labelledby="threads-dialog-title"
      >
        <DialogTitle id="threads-dialog-title">Fils de discussion</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
            Maggie range la conversation par sujet. Supprimer un fil efface aussi ses messages.
          </Typography>
          <ContextList contexts={contexts} onDelete={ask} />
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Fermer</Button>
        </DialogActions>
      </Dialog>
      <Dialog open={confirming} onClose={() => setConfirming(false)} aria-labelledby="threads-confirm-title">
        <DialogTitle id="threads-confirm-title">
          {target ? `Supprimer le fil « ${target.label} » ?` : 'Supprimer ce fil ?'}
        </DialogTitle>
        <DialogContent>
          <DialogContentText>{target ? `${deletionMessage(target.messageCount)}.` : null}</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirming(false)}>Annuler</Button>
          <Button color="error" onClick={confirm}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
