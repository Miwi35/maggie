import { useState } from 'react'
import type { ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'

/** The page around the settings: « Enregistré », loading and load error (the title is the page heading). */
export const TaskSettingsFrame = ({
  loadFailed,
  ready,
  saved,
  children,
}: {
  loadFailed: boolean
  ready: boolean
  saved: boolean
  children: ReactNode
}) => {
  if (loadFailed && !ready) {
    return (
      <Box maxWidth={700} mx="auto" mt={2}>
        <Alert severity="error">Impossible de charger les réglages des tâches</Alert>
      </Box>
    )
  }

  if (!ready) {
    return (
      <Box display="flex" justifyContent="center" mt={4}>
        <CircularProgress />
      </Box>
    )
  }

  return (
    <Box maxWidth={700} mx="auto" mt={2} mb={4}>
      {saved && (
        <Typography variant="caption" color="text.secondary" role="status" display="block" mb={2}>
          Enregistré
        </Typography>
      )}
      {children}
    </Box>
  )
}

/** « Valeurs par défaut », behind a confirmation. */
export const DefaultsButton = ({ onConfirm }: { onConfirm: () => void }) => {
  const [open, setOpen] = useState(false)

  return (
    <>
      <Stack direction="row" justifyContent="flex-end">
        <Button variant="outlined" onClick={() => setOpen(true)}>
          Valeurs par défaut
        </Button>
      </Stack>
      <Dialog open={open} onClose={() => setOpen(false)} aria-labelledby="task-settings-defaults-title">
        <DialogTitle id="task-settings-defaults-title">Revenir aux valeurs par défaut ?</DialogTitle>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Annuler</Button>
          <Button
            variant="contained"
            onClick={() => {
              setOpen(false)
              onConfirm()
            }}
          >
            Confirmer
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
