import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardActions from '@mui/material/CardActions'
import CardContent from '@mui/material/CardContent'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useNotify } from 'react-admin'
import { formatCents } from './accountTypes'
import { useAccountLink } from './useAccountLink'
import type { LinkCandidate, LinkPreview } from './useAccountLink'

interface Props {
  accountId: string | undefined
  accountName: string
  /** Only a hand-typed account is offered the link. */
  manual: boolean
  /** The page reads itself again once the link is written. */
  onLinked: () => void
}

const previewText = ({ moved, dropped }: LinkPreview) =>
  `${moved === 1 ? '1 mouvement' : `${moved} mouvements`} rejoignent ce compte` +
  (dropped > 0 ? `, ${dropped === 1 ? '1 doublon est écarté' : `${dropped} doublons sont écartés`}.` : '.')

/** « Rattacher au compte de la banque »: a card on a hand-typed account that a bank-created one could join (MAG-823). */
export const AccountLinkCard = ({ accountId, accountName, manual, onLinked }: Props) => {
  const notify = useNotify()
  const { candidates, preview, confirm, error, clearError } = useAccountLink(accountId, manual)
  const [open, setOpen] = useState(false)
  const [chosen, setChosen] = useState<LinkCandidate | null>(null)
  const [figures, setFigures] = useState<LinkPreview | null>(null)

  if (!manual || candidates.length === 0) return null
  const first = candidates[0]

  const close = () => {
    setOpen(false)
    setChosen(null)
    setFigures(null)
    clearError()
  }

  const choose = async (candidate: LinkCandidate | null) => {
    setChosen(candidate)
    setFigures(null)
    if (candidate) setFigures(await preview(candidate.id))
  }

  const link = async () => {
    if (!chosen) return
    if (await confirm(chosen.id)) {
      close()
      notify('Compte rattaché.', { type: 'success' })
      onLinked()
    }
  }

  return (
    <>
      <Card variant="outlined" sx={{ mb: 2 }}>
        <CardContent>
          <Typography variant="subtitle1">Rattacher au compte de la banque</Typography>
          <Typography variant="body2" color="text.secondary">
            {first.bank || 'La banque'} a créé « {first.name} » · {formatCents(first.balanceCents, first.currency)}
          </Typography>
        </CardContent>
        <CardActions>
          <Button
            onClick={() => {
              setOpen(true)
              if (candidates.length === 1) void choose(first)
            }}
          >
            Rattacher…
          </Button>
        </CardActions>
      </Card>
      <Dialog open={open} onClose={close} fullWidth maxWidth="sm">
        <DialogTitle>Rattacher « {accountName} » au compte de la banque</DialogTitle>
        <DialogContent>
          <Autocomplete
            sx={{ mt: 1 }}
            options={candidates}
            value={chosen}
            getOptionLabel={(option) => `${option.name} · ${formatCents(option.balanceCents, option.currency)}`}
            onChange={(_, value) => void choose(value)}
            renderInput={(params) => <TextField {...params} label="Compte de la banque" />}
          />
          {error && (
            <Alert severity="error" sx={{ mt: 2 }}>
              {error}
            </Alert>
          )}
          {figures && (
            <Typography sx={{ mt: 2 }} role="status">
              {previewText(figures)}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>Annuler</Button>
          <Button variant="contained" disabled={!figures} onClick={() => void link()}>
            Rattacher
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
