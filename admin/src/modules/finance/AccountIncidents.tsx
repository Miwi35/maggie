import { useState } from 'react'
import type { ReactNode } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Snackbar from '@mui/material/Snackbar'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import { Link, useCreatePath, useGetOne } from 'react-admin'
import { Placeholder } from '../../components/list/ListEmpty'
import { Amount } from './AmountField'
import { INCIDENT_KIND_LABEL } from './useAccountIncidents'
import type { AccountIncident } from './useAccountIncidents'

const asDay = (iso: string) => iso.split('-').reverse().join('/')

const Leg = ({ title, id }: { title: string; id: string | null }) => {
  const createPath = useCreatePath()
  const { data, isPending, error } = useGetOne(
    'transactions',
    { id: `/api/transactions/${id}` },
    { enabled: id !== null },
  )

  return (
    <Box sx={{ mb: 2 }}>
      <Typography variant="overline" color="text.secondary">
        {title}
      </Typography>
      {id === null && <Typography>Transaction non retrouvée</Typography>}
      {id !== null && isPending && <Typography color="text.secondary">Chargement…</Typography>}
      {id !== null && error && <Alert severity="error">Impossible de lire cette transaction</Alert>}
      {data && (
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'baseline', justifyContent: 'space-between' }}>
          <span>
            {asDay(String(data.bookedAt).slice(0, 10))} · {data.label as string}
          </span>
          <Amount cents={data.amountCents as number} currency={data.currency as string} signed />
          <Link to={createPath({ resource: 'transactions', type: 'edit', id: data.id })}>Ouvrir la fiche</Link>
        </Box>
      )}
    </Box>
  )
}

/** The two original operations of a rejection: the payment, and the credit that gave it back. */
const IncidentDetail = ({ incident, onClose }: { incident: AccountIncident; onClose: () => void }) => (
  <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="incident-title">
    <DialogTitle id="incident-title">
      {incident.counterpartyName} · {INCIDENT_KIND_LABEL[incident.kind]}
    </DialogTitle>
    <DialogContent>
      <Leg title="Transaction débitée" id={incident.debitId} />
      <Leg title="Transaction rendue" id={incident.creditId} />
    </DialogContent>
    <DialogActions>
      <Button onClick={onClose}>Fermer</Button>
    </DialogActions>
  </Dialog>
)

/** How long « Annuler » stays on offer after « Régularisé ». */
export const UNDO_DELAY_MS = 5000

const incidentKey = (incident: AccountIncident) => `${incident.debitId}|${incident.creditId}`

const IncidentTable = ({
  incidents,
  onOpen,
  action,
}: {
  incidents: AccountIncident[]
  onOpen: (incident: AccountIncident) => void
  action: (incident: AccountIncident) => ReactNode
}) => (
  <Table size="small">
    <TableHead>
      <TableRow>
        <TableCell>Date</TableCell>
        <TableCell>Créancier</TableCell>
        <TableCell>Incident</TableCell>
        <TableCell align="right">Montant</TableCell>
        <TableCell />
      </TableRow>
    </TableHead>
    <TableBody>
      {incidents.map((incident) => (
        <TableRow key={incidentKey(incident)} hover sx={{ cursor: 'pointer' }} onClick={() => onOpen(incident)}>
          <TableCell>{asDay(incident.bookedAt)}</TableCell>
          <TableCell>{incident.counterpartyName}</TableCell>
          <TableCell>{INCIDENT_KIND_LABEL[incident.kind]}</TableCell>
          <TableCell align="right">
            <Amount cents={incident.amountCents} />
          </TableCell>
          {/* The button acts on the line, it does not open it. */}
          <TableCell align="right" onClick={(event) => event.stopPropagation()}>
            {action(incident)}
          </TableCell>
        </TableRow>
      ))}
    </TableBody>
  </Table>
)

/**
 * The rejections of an account: the pile still to deal with, each with
 * « Régularisé » — which takes it out, with « Annuler » for a few seconds —
 * and, folded below, those already settled (MAG-424).
 */
export const AccountIncidents = ({
  incidents,
  settled = [],
  error,
  actionError = null,
  onSettle,
  onUnsettle,
}: {
  incidents: AccountIncident[] | null
  settled?: AccountIncident[]
  error: string | null
  actionError?: string | null
  onSettle?: (incident: AccountIncident) => Promise<boolean>
  onUnsettle?: (incident: AccountIncident) => Promise<boolean>
}) => {
  const [open, setOpen] = useState<AccountIncident | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  // The rejection « Annuler » would put back, while it is on offer.
  const [undo, setUndo] = useState<AccountIncident | null>(null)

  const run = async (
    incident: AccountIncident,
    gesture: ((incident: AccountIncident) => Promise<boolean>) | undefined,
  ): Promise<boolean> => {
    if (!gesture) return false
    setBusy(incidentKey(incident))
    try {
      return await gesture(incident)
    } finally {
      setBusy(null)
    }
  }

  const settle = async (incident: AccountIncident) => {
    if (await run(incident, onSettle)) setUndo(incident)
  }

  const cancel = async () => {
    const incident = undo
    setUndo(null)
    if (incident) await run(incident, onUnsettle)
  }

  if (error && incidents === null) return <Alert severity="error">{error}</Alert>
  if (incidents === null) return <Typography color="text.secondary">Chargement…</Typography>

  return (
    <>
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
      {actionError && <Alert severity="error" sx={{ mb: 1 }}>{actionError}</Alert>}
      {incidents.length === 0 && settled.length > 0 ? (
        <Typography color="text.secondary" sx={{ py: 1 }}>
          Aucun rejet à traiter.
        </Typography>
      ) : incidents.length === 0 ? (
        <Placeholder
          title="Aucun incident sur ce compte"
          description="Un prélèvement ou un virement rejeté par la banque apparaîtra ici, une ligne par rejet."
        />
      ) : (
        <IncidentTable
          incidents={incidents}
          onOpen={setOpen}
          action={(incident) => (
            <Button
              size="small"
              variant="outlined"
              disabled={busy !== null}
              onClick={() => settle(incident)}
              aria-label={`Régularisé : ${incident.counterpartyName}`}
            >
              Régularisé
            </Button>
          )}
        />
      )}
      {settled.length > 0 && (
        <Accordion disableGutters elevation={0} sx={{ mt: 2, '&:before': { display: 'none' } }}>
          <AccordionSummary expandIcon={<ExpandMoreIcon />}>
            <Typography>Régularisés ({settled.length})</Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ p: 0 }}>
            <IncidentTable
              incidents={settled}
              onOpen={setOpen}
              action={(incident) => (
                <Button
                  size="small"
                  disabled={busy !== null}
                  onClick={() => run(incident, onUnsettle)}
                  aria-label={`Remettre dans la pile : ${incident.counterpartyName}`}
                >
                  Remettre dans la pile
                </Button>
              )}
            />
          </AccordionDetails>
        </Accordion>
      )}
      {open && <IncidentDetail incident={open} onClose={() => setOpen(null)} />}
      <Snackbar
        // A second « Régularisé » while the bar is up gets its own full delay.
        key={undo ? incidentKey(undo) : undefined}
        open={undo !== null}
        message={undo ? `Rejet ${undo.counterpartyName} régularisé` : undefined}
        autoHideDuration={UNDO_DELAY_MS}
        onClose={(_event, reason) => {
          // « Annuler » stays until the delay is over, however the page is clicked.
          if (reason === 'timeout') setUndo(null)
        }}
        action={
          <Button color="primary" size="small" onClick={cancel}>
            Annuler
          </Button>
        }
      />
    </>
  )
}
