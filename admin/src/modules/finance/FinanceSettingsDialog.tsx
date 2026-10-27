import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import type { ReactNode } from 'react'

/** The « Régler » dialog: settings live here, never on the page they shape. */
export const FinanceSettingsDialog = ({
  open,
  onClose,
  children,
}: {
  open: boolean
  onClose: () => void
  children: ReactNode
}) => (
  <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="finance-settings-title">
    <DialogTitle id="finance-settings-title">Réglages</DialogTitle>
    <DialogContent>{children}</DialogContent>
    <DialogActions>
      <Button onClick={onClose}>Fermer</Button>
    </DialogActions>
  </Dialog>
)
