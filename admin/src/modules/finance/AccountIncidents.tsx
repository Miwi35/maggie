import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
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
      {id === null && <Typography>Opération non retrouvée</Typography>}
      {id !== null && isPending && <Typography color="text.secondary">Chargement…</Typography>}
      {id !== null && error && <Alert severity="error">Impossible de lire cette opération</Alert>}
      {data && (
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'baseline', justifyContent: 'space-between' }}>
          <span>
            {asDay(String(data.bookedAt).slice(0, 10))} · {data.label as string}
          </span>
          <Amount cents={data.amountCents as number} currency={data.currency as string} signed />
          <Link to={createPath({ resource: 'transactions', type: 'edit', id: data.id })}>Éditer</Link>
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
      <Leg title="Opération débitée" id={incident.debitId} />
      <Leg title="Opération rendue" id={incident.creditId} />
    </DialogContent>
    <DialogActions>
      <Button onClick={onClose}>Fermer</Button>
    </DialogActions>
  </Dialog>
)

export const AccountIncidents = ({
  incidents,
  error,
}: {
  incidents: AccountIncident[] | null
  error: string | null
}) => {
  const [open, setOpen] = useState<AccountIncident | null>(null)

  if (error && incidents === null) return <Alert severity="error">{error}</Alert>
  if (incidents === null) return <Typography color="text.secondary">Chargement…</Typography>
  if (incidents.length === 0) {
    return (
      <Placeholder
        title="Aucun incident sur ce compte"
        description="Un prélèvement ou un virement rejeté par la banque apparaîtra ici, une ligne par rejet."
      />
    )
  }

  return (
    <>
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Date</TableCell>
            <TableCell>Créancier</TableCell>
            <TableCell>Incident</TableCell>
            <TableCell align="right">Montant</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {incidents.map((incident) => (
            <TableRow
              key={`${incident.debitId}|${incident.creditId}`}
              hover
              sx={{ cursor: 'pointer' }}
              onClick={() => setOpen(incident)}
            >
              <TableCell>{asDay(incident.bookedAt)}</TableCell>
              <TableCell>{incident.counterpartyName}</TableCell>
              <TableCell>{INCIDENT_KIND_LABEL[incident.kind]}</TableCell>
              <TableCell align="right">
                <Amount cents={incident.amountCents} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {open && <IncidentDetail incident={open} onClose={() => setOpen(null)} />}
    </>
  )
}
