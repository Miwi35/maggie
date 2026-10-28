import { useState } from 'react'
import type { FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useNotify } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { transferCount } from './contactsApi'
import { formatDay } from './recurringOperations'
import { useContactCounterparties } from './useContactCounterparties'

/** « 3 virements · dernier le 12/10/2026 ». */
const usage = (transactionCount: number, lastSeenOn: string | null): string =>
  lastSeenOn ? `${transferCount(transactionCount)} · dernier le ${formatDay(lastSeenOn)}` : transferCount(transactionCount)

/**
 * « Ses comptes » on the contact's page: the labels their transfers carry, one
 * added by hand, and those that may be theirs — « Peut-être aussi Tom ».
 */
export const ContactCounterpartiesSection = ({ contactId, contactName }: { contactId: string; contactName: string }) => {
  const notify = useNotify()
  const { counterparties, suggestions, error, busy, add, remove } = useContactCounterparties(contactId)
  const [label, setLabel] = useState('')

  const attempt = async (action: () => Promise<void>): Promise<boolean> => {
    try {
      await action()

      return true
    } catch (e) {
      notify(e instanceof Error ? e.message : 'Une erreur est survenue.', { type: 'error' })

      return false
    }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (label.trim() === '') return
    if (await attempt(() => add(label))) setLabel('')
  }

  return (
    <Box component="section" aria-label="Ses comptes" sx={{ px: 2, pb: 2, maxWidth: 680 }}>
      <FormSection title="Ses comptes" />

      {error && <Alert severity="error">{error}</Alert>}
      {!counterparties && !error && <LinearProgress aria-label="Chargement" />}

      {counterparties &&
        (counterparties.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Aucun libellé pour l'instant.
          </Typography>
        ) : (
          <List dense disablePadding aria-label="Libellés liés">
            {counterparties.map((counterparty) => (
              <ListItem
                key={counterparty.id}
                disableGutters
                secondaryAction={
                  <Button
                    size="small"
                    color="inherit"
                    disabled={busy}
                    onClick={() => void attempt(() => remove(counterparty.id))}
                    aria-label={`Retirer ${counterparty.label}`}
                  >
                    Retirer
                  </Button>
                }
              >
                <ListItemText
                  primary={counterparty.label}
                  secondary={usage(counterparty.transactionCount, counterparty.lastSeenOn)}
                />
              </ListItem>
            ))}
          </List>
        ))}

      <Stack component="form" direction="row" spacing={1} alignItems="center" sx={{ mt: 2 }} onSubmit={submit}>
        <TextField
          size="small"
          fullWidth
          label="Ajouter un libellé"
          placeholder="VIR. DE TOM MARTIN"
          value={label}
          disabled={busy}
          onChange={(e) => setLabel(e.target.value)}
        />
        <Button type="submit" variant="outlined" disabled={busy || label.trim() === ''}>
          Ajouter
        </Button>
      </Stack>

      {suggestions.length > 0 && (
        <Box sx={{ mt: 3 }}>
          <Typography variant="subtitle2">Peut-être aussi {contactName} :</Typography>
          <List dense disablePadding aria-label="Suggestions">
            {suggestions.map((suggestion) => (
              <ListItem
                key={suggestion.label}
                disableGutters
                secondaryAction={
                  <Button
                    size="small"
                    disabled={busy}
                    onClick={() => void attempt(() => add(suggestion.label))}
                    aria-label={`Ajouter ${suggestion.label}`}
                  >
                    + Ajouter
                  </Button>
                }
              >
                <ListItemText
                  primary={suggestion.label}
                  secondary={usage(suggestion.transactionCount, suggestion.lastSeenOn)}
                />
              </ListItem>
            ))}
          </List>
        </Box>
      )}
    </Box>
  )
}
