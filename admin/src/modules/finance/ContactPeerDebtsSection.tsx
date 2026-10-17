import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import LinearProgress from '@mui/material/LinearProgress'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import Typography from '@mui/material/Typography'
import { FormSection } from '../../components/form/FormSection'
import { bareId } from './envelopeTree'
import { formatCents } from './accountTypes'
import { debtSentence, fetchPeerDebtsOverview } from './peerDebtsApi'
import type { PeerDebtWithHistory } from './peerDebtsApi'
import { formatDay } from './recurringOperations'

/** « En cours » and the transfers that gave things back, as the contact's page shows them (MAG-455). */
export const ContactPeerDebtsSection = ({ contactId }: { contactId: string }) => {
  const [debts, setDebts] = useState<PeerDebtWithHistory[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    fetchPeerDebtsOverview({ contact: contactId })
      .then((all) => live && setDebts(all))
      .catch((e: unknown) => live && setError(e instanceof Error ? e.message : 'Impossible de lire les créances.'))

    return () => {
      live = false
    }
  }, [contactId])

  const id = bareId(contactId)
  const open = debts
    ? debts
        .filter((debt) => debt.status === 'open')
        .flatMap((debt) =>
          debt.shares
            .filter((share) => share.contactId === id && share.remainingCents > 0)
            .map((share) => ({
              key: `${debt.id}-${share.id}`,
              object: debt.object,
              createdAt: debt.createdAt,
              remainingCents: share.remainingCents,
              direction: debt.direction,
            })),
        )
    : []
  const history = debts
    ? debts
        .flatMap((debt) => debt.history.filter((entry) => entry.contactId === id).map((entry) => ({ ...entry, object: debt.object, direction: debt.direction })))
        .sort((a, b) => b.bookedAt.localeCompare(a.bookedAt))
    : []

  return (
    <>
      <Box component="section" aria-label="En cours" sx={{ px: 2, pb: 2, maxWidth: 680 }}>
        <FormSection title="En cours" description="Les créances et les dettes dont sa part n’est pas encore soldée." />

        {error && <Alert severity="error">{error}</Alert>}
        {!debts && !error && <LinearProgress aria-label="Chargement des créances" />}

        {debts &&
          (open.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Rien en cours.
            </Typography>
          ) : (
            <List dense disablePadding aria-label="Créances en cours">
              {open.map((debt) => (
                <ListItem key={debt.key} disableGutters>
                  <ListItemText primary={debt.object} secondary={formatDay(debt.createdAt)} />
                  <Typography variant="body2" sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>
                    {debtSentence(debt.direction, debt.remainingCents)}
                  </Typography>
                </ListItem>
              ))}
            </List>
          ))}
      </Box>

      {debts && (
        <Box component="section" aria-label="Virements affectés" sx={{ px: 2, pb: 2, maxWidth: 680 }}>
          <FormSection title="Virements affectés" description="Les virements qui ont soldé une partie de ses créances ou de ses dettes." />
          {history.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Aucun virement affecté.
            </Typography>
          ) : (
            <List dense disablePadding aria-label="Historique des virements">
              {history.map((entry) => (
                <ListItem key={`${entry.transactionId}-${entry.object}`} disableGutters>
                  <ListItemText primary={entry.label} secondary={`${formatDay(entry.bookedAt)} · ${entry.object}`} />
                  <Typography variant="body2" sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>
                    {formatCents(entry.amountCents)}
                  </Typography>
                </ListItem>
              ))}
            </List>
          )}
        </Box>
      )}
    </>
  )
}
