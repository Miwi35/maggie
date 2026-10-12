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
import LinkIcon from '@mui/icons-material/Link'
import { useNotify, useRefresh } from 'react-admin'
import { Amount } from './AmountField'
import { formatDay } from './recurringOperations'
import { useAttachRecurringOperations } from './useRecurringOperations'
import type { AttachReport, RecurringProposal } from './recurringOperations'

interface PreviewProps {
  report: AttachReport
  /** Keeps the lines of the report this preview is about; every line by default. */
  only?: (line: RecurringProposal) => boolean
  title?: string
  /** The sentence above the lines, when the default one does not fit. */
  intro?: string
  running: boolean
  onClose: () => void
  onConfirm: () => void
}

/**
 * What the catch-up would attach, read before it writes. The lines are the
 * transactions that settle an occurrence; the ones that only break a tolerance
 * are proposals and stay in their panel.
 */
export const RecurringCatchUpPreview = ({
  report,
  only = () => true,
  title = 'Rattacher l’historique',
  intro,
  running,
  onClose,
  onConfirm,
}: PreviewProps) => {
  const lines = report.attachments.filter(only)
  const proposals = report.proposals.filter(only).length

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md" aria-labelledby="recurring-catch-up-title">
      <DialogTitle id="recurring-catch-up-title">{title}</DialogTitle>
      <DialogContent>
        {lines.length === 0 ? (
          <Typography>
            Aucune transaction à rattacher ({report.scanned} analysée(s)).
            {proposals > 0 && ` ${proposals} rattachement(s) restent à confirmer dans les propositions.`}
          </Typography>
        ) : (
          <>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {intro ?? `${lines.length} transaction(s) sur ${report.scanned} analysée(s).`} Rien n’est écrit tant que
              vous n’avez pas confirmé.
              {proposals > 0 && ` ${proposals} autre(s) restent à confirmer dans les propositions.`}
            </Typography>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Transaction</TableCell>
                  <TableCell>Série</TableCell>
                  <TableCell>Échéance</TableCell>
                  <TableCell align="right">Montant</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {lines.map((line) => (
                  <TableRow key={line.transactionId}>
                    <TableCell>
                      {formatDay(line.bookedAt)} · {line.label}
                    </TableCell>
                    <TableCell>{line.recurringOperationLabel}</TableCell>
                    <TableCell>{line.occurrenceOn ? formatDay(line.occurrenceOn) : '—'}</TableCell>
                    <TableCell align="right">
                      <Amount cents={line.amountCents} signed />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{lines.length === 0 ? 'Fermer' : 'Annuler'}</Button>
        {lines.length > 0 && (
          <Button variant="contained" disabled={running} onClick={onConfirm}>
            Rattacher ces {lines.length} transaction(s)
          </Button>
        )}
      </DialogActions>
    </Dialog>
  )
}

/**
 * The catch-up of the recurring operations, next to the one of the
 * categorization rules. It always rehearses first and shows the lines.
 */
export const RecurringCatchUpButton = ({ onDone }: { onDone?: () => void }) => {
  const { attach, running } = useAttachRecurringOperations()
  const notify = useNotify()
  const refresh = useRefresh()
  const [preview, setPreview] = useState<AttachReport | null>(null)

  const rehearse = async () => {
    const report = await attach(true)
    if (report === null) {
      notify('Impossible de rattacher l’historique', { type: 'error' })

      return
    }
    setPreview(report)
  }

  const apply = async () => {
    const report = await attach(false)
    if (report === null) {
      notify('Impossible de rattacher l’historique', { type: 'error' })

      return
    }
    setPreview(null)
    notify(`${report.attached} transaction(s) rattachée(s) sur ${report.scanned} analysée(s)`, { type: 'info' })
    refresh()
    onDone?.()
  }

  return (
    <>
      <Button startIcon={<LinkIcon />} onClick={rehearse} disabled={running}>
        Rattacher l’historique
      </Button>
      {preview && (
        <RecurringCatchUpPreview report={preview} running={running} onClose={() => setPreview(null)} onConfirm={apply} />
      )}
    </>
  )
}
