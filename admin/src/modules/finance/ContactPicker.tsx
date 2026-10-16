import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import LinearProgress from '@mui/material/LinearProgress'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { createContact, fetchContacts } from './contactsApi'
import type { Contact } from './contactsApi'

/** Case and accents aside: « tom » finds « Tóm », « Tom » is an exact match of « tom ». */
const fold = (text: string): string =>
  text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim()

/**
 * Picks one of the owner's contacts, or creates it from what was typed —
 * « + Créer « Tom » » when no contact bears exactly that name (MAG-450).
 *
 * The contacts are read once per opening and filtered here: an owner knows a
 * few dozen people. `onPick` receives the contact, created first when needed;
 * what it is picked for (a label, a debt) is the caller's business.
 */
export const ContactPicker = ({
  open,
  title = 'Associer à un contact',
  initialQuery = '',
  onClose,
  onPick,
}: {
  open: boolean
  title?: string
  initialQuery?: string
  onClose: () => void
  onPick: (contact: Contact) => void
}) => {
  const [contacts, setContacts] = useState<Contact[] | null>(null)
  const [query, setQuery] = useState(initialQuery)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    if (!open) return
    let live = true
    setContacts(null)
    setError(null)
    setQuery(initialQuery)
    fetchContacts()
      .then((list) => live && setContacts(list))
      .catch((e: unknown) => {
        if (!live) return
        setContacts([])
        setError(e instanceof Error ? e.message : 'Impossible de lire vos contacts.')
      })

    return () => {
      live = false
    }
  }, [open, initialQuery])

  const wanted = fold(query)
  const shown = useMemo(
    () => (contacts ?? []).filter((contact) => fold(contact.name).includes(wanted)),
    [contacts, wanted],
  )
  const canCreate = wanted !== '' && contacts !== null && !contacts.some((c) => fold(c.name) === wanted)

  const create = async () => {
    setCreating(true)
    setError(null)
    try {
      const created = await createContact(query)
      // Kept in the list: if what follows the pick fails, picking again must not create a twin.
      setContacts((list) => [...(list ?? []), created])
      onPick(created)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Impossible de créer ce contact.')
    } finally {
      setCreating(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs" aria-labelledby="contact-picker-title">
      <DialogTitle id="contact-picker-title">{title}</DialogTitle>
      <DialogContent>
        <TextField
          autoFocus
          fullWidth
          size="small"
          margin="dense"
          label="Rechercher un contact"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {error && (
          <Alert severity="error" sx={{ mt: 1 }}>
            {error}
          </Alert>
        )}
        {contacts === null ? (
          <LinearProgress aria-label="Chargement" sx={{ mt: 2 }} />
        ) : (
          <List dense aria-label="Contacts">
            {shown.map((contact) => (
              <ListItemButton key={contact.id} disabled={creating} onClick={() => onPick(contact)}>
                <ListItemText primary={contact.name} secondary={contact.notes} />
              </ListItemButton>
            ))}
            {canCreate && (
              <ListItemButton disabled={creating} onClick={() => void create()}>
                <ListItemText primary={`+ Créer « ${query.trim()} »`} />
              </ListItemButton>
            )}
            {shown.length === 0 && !canCreate && (
              <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
                Aucun contact
              </Typography>
            )}
          </List>
        )}
      </DialogContent>
    </Dialog>
  )
}
