import { useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import { useNotify, useRefresh } from 'react-admin'
import { Amount } from './AmountField'
import { useDetectInternalTransfers } from './useDetectInternalTransfers'
import type { DetectResult } from './useDetectInternalTransfers'

const asDay = (iso: string) => iso.split('-').reverse().join('/')

const Preview = ({
  report,
  running,
  onClose,
  onConfirm,
}: {
  report: DetectResult
  running: boolean
  onClose: () => void
  onConfirm: () => void
}) => (
  <Dialog open onClose={onClose} fullWidth maxWidth="md" aria-labelledby="detect-transfers-title">
    <DialogTitle id="detect-transfers-title">Virements internes détectés</DialogTitle>
    <DialogContent>
      {report.matched === 0 ? (
        <Typography>
          Aucun virement interne à apparier ({report.scanned} ligne(s) analysée(s)).
        </Typography>
      ) : (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {report.matched} paire(s) sur {report.scanned} ligne(s) analysée(s). Rien n’est écrit tant que vous
            n’avez pas confirmé : une paire retire ses deux lignes de tous les chiffres.
          </Typography>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Sortie</TableCell>
                <TableCell>Entrée</TableCell>
                <TableCell align="right">Montant</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {report.pairs.map((pair) => (
                <TableRow key={`${pair.transactionId}/${pair.counterpartId}`}>
                  <TableCell>
                    {asDay(pair.bookedAt)} · {pair.label}
                  </TableCell>
                  <TableCell>
                    {asDay(pair.counterpartBookedAt)} · {pair.counterpartLabel}
                  </TableCell>
                  <TableCell align="right">
                    <Amount cents={Math.abs(pair.amountCents)} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </>
      )}
    </DialogContent>
    <DialogActions>
      <Button onClick={onClose}>{report.matched === 0 ? 'Fermer' : 'Annuler'}</Button>
      {report.matched > 0 && (
        <Button variant="contained" disabled={running} onClick={onConfirm}>
          Marquer ces {report.matched} paire(s)
        </Button>
      )}
    </DialogActions>
  </Dialog>
)

/**
 * The catch-up of the detection, next to the one of the categorization rules.
 * It always rehearses first and shows the pairs: four figures of the
 * dashboard move with them.
 */
export const DetectInternalTransfersButton = () => {
  const { detect, running } = useDetectInternalTransfers()
  const notify = useNotify()
  const refresh = useRefresh()
  const [preview, setPreview] = useState<DetectResult | null>(null)

  const rehearse = async () => {
    const report = await detect(true)
    if (report === null) {
      notify('Impossible de détecter les virements internes', { type: 'error' })

      return
    }
    setPreview(report)
  }

  const apply = async () => {
    const report = await detect(false)
    if (report === null) {
      notify('Impossible de marquer les virements internes', { type: 'error' })

      return
    }
    setPreview(null)
    notify(`${report.matched} virement(s) interne(s) marqué(s) sur ${report.scanned} ligne(s) analysée(s)`, {
      type: 'info',
    })
    refresh()
  }

  return (
    <>
      <Button startIcon={<SwapHorizIcon />} onClick={rehearse} disabled={running}>
        Détecter les virements internes
      </Button>
      {preview && (
        <Preview report={preview} running={running} onClose={() => setPreview(null)} onConfirm={apply} />
      )}
    </>
  )
}
