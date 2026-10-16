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
import { debtSentence, fetchPeerDebts } from './peerDebtsApi'
import { formatDay } from './recurringOperations'

/** One open créance as this contact sees it: their share only. */
interface OpenDebt {
  id: string
  object: string
  createdAt: string
  remainingCents: number
  direction: 'owed_to_me' | 'i_owe'
}

/**
 * « En cours » on the contact's page (MAG-451): the créances still open with
 * them, each with what is left of their own share — the gîte, « te doit 200 € ».
 * Read from `/api/peer_debts`, filtered here: an owner has a handful of them.
 */
export const ContactPeerDebtsSection = ({ contactId }: { contactId: string }) => {
  const [debts, setDebts] = useState<OpenDebt[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    const id = bareId(contactId)
    fetchPeerDebts()
      .then((all) => {
        if (!live) return
        setDebts(
          all.flatMap((debt) =>
            debt.status !== 'open'
              ? []
              : debt.shares
                  .filter((share) => share.contactId === id && share.remainingCents > 0)
                  .map((share) => ({
                    id: `${debt.id}-${share.id}`,
                    object: debt.object,
                    createdAt: debt.createdAt,
                    remainingCents: share.remainingCents,
                    direction: debt.direction,
                  })),
          ),
        )
      })
      .catch((e: unknown) => live && setError(e instanceof Error ? e.message : 'Impossible de lire les créances.'))

    return () => {
      live = false
    }
  }, [contactId])

  return (
    <Box component="section" aria-label="En cours" sx={{ px: 2, pb: 2, maxWidth: 680 }}>
      <FormSection title="En cours" description="Les dépenses partagées dont sa part n’est pas encore remboursée." />

      {error && <Alert severity="error">{error}</Alert>}
      {!debts && !error && <LinearProgress aria-label="Chargement des créances" />}

      {debts &&
        (debts.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Rien en cours.
          </Typography>
        ) : (
          <List dense disablePadding aria-label="Créances en cours">
            {debts.map((debt) => (
              <ListItem key={debt.id} disableGutters>
                <ListItemText primary={debt.object} secondary={formatDay(debt.createdAt)} />
                <Typography variant="body2" sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>
                  {debtSentence(debt.direction, debt.remainingCents)}
                </Typography>
              </ListItem>
            ))}
          </List>
        ))}
    </Box>
  )
}
