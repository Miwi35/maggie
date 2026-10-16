import { useCallback, useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import { Confirm, useNotify } from 'react-admin'
import { Link } from 'react-router-dom'
import { formatCents } from './accountTypes'
import { ContactPicker } from './ContactPicker'
import type { Contact } from './contactsApi'
import { centsToInput, fetchMyShare, parseEuros, removeMyShare, saveMyShare, splitEqually } from './peerDebtsApi'
import type { MyShare, MyShareInput } from './peerDebtsApi'

const STEP_CENTS = 100

/** One person who owes part of the spend; `amountText` only counts once an amount was typed by hand. */
interface Person {
  contactId: string
  contactName: string
  amountText: string
}

/** True when the stored amounts are what an equal split would give: the split stays automatic. */
const isEqualSplit = (amounts: number[]): boolean => {
  const total = amounts.reduce((sum, a) => sum + a, 0)
  const equal = splitEqually(total, amounts.length)

  return amounts.every((a, i) => a === equal[i])
}

/** The part the owner keeps when she first shares a spend: half, to the cent. */
const defaultPart = (totalCents: number): number => Math.floor(totalCents / 2)

/**
 * « Partage » on the transaction sheet (MAG-451): the owner keeps her part of a
 * spend — only it counts in the envelopes — and the rest becomes a créance
 * split between people, equally until she types an amount by hand.
 *
 * It writes on its own, through `/api/finance/transactions/{id}/my-share`, not
 * with the sheet's « Enregistrer »: the part and the créance go together.
 */
export const TransactionShareSection = ({ transactionId, label }: { transactionId: string; label: string }) => {
  const notify = useNotify()
  const [share, setShare] = useState<MyShare | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [enabled, setEnabled] = useState(false)
  const [partText, setPartText] = useState('')
  const [people, setPeople] = useState<Person[]>([])
  const [manual, setManual] = useState(false)
  const [object, setObject] = useState('')
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [picking, setPicking] = useState(false)
  const [confirming, setConfirming] = useState(false)

  /** The form as the API last answered it. */
  const reset = useCallback(
    (current: MyShare) => {
      const total = Math.abs(current.amountCents)
      const debt = current.peerDebt
      const amounts = debt?.shares.map((s) => s.amountCents) ?? []
      setShare(current)
      setEnabled(current.myShareCents !== null)
      setPartText(centsToInput(current.myShareCents ?? defaultPart(total)))
      setPeople(
        (debt?.shares ?? []).map((s) => ({
          contactId: s.contactId,
          contactName: s.contactName,
          amountText: centsToInput(s.amountCents),
        })),
      )
      setManual(amounts.length > 0 && !isEqualSplit(amounts))
      setObject(debt?.object ?? label)
      setNotes(debt?.notes ?? '')
      setError(null)
    },
    [label],
  )

  useEffect(() => {
    let live = true
    fetchMyShare(transactionId)
      .then((current) => live && reset(current))
      .catch((e: unknown) => live && setLoadError(e instanceof Error ? e.message : 'Impossible de lire le partage.'))

    return () => {
      live = false
    }
  }, [transactionId, reset])

  const total = Math.abs(share?.amountCents ?? 0)
  const partCents = parseEuros(partText)
  const partValid = partCents !== null && partCents < total
  const owedCents = partValid ? total - partCents : null

  const amounts = useMemo<(number | null)[]>(
    () => (manual ? people.map((p) => parseEuros(p.amountText)) : splitEqually(owedCents ?? 0, people.length)),
    [manual, people, owedCents],
  )
  const typedSum = amounts.reduce<number>((sum, a) => sum + (a ?? 0), 0)
  const sumMismatch = manual && owedCents !== null && (amounts.some((a) => a === null) || typedSum !== owedCents)

  if (loadError) return <Alert severity="error">{loadError}</Alert>
  if (!share) return <LinearProgress aria-label="Chargement du partage" />
  if (!share.canBeShared) {
    return (
      <Typography variant="body2" color="text.secondary">
        Seule une dépense peut être partagée.
      </Typography>
    )
  }

  const shared = share.myShareCents !== null

  const toggle = (checked: boolean) => {
    if (checked) {
      setEnabled(true)
      return
    }
    if (shared) {
      setConfirming(true)
      return
    }
    reset(share)
  }

  const step = (delta: number) => {
    const from = partCents ?? 0
    setPartText(centsToInput(Math.min(Math.max(from + delta, 0), Math.max(total - 1, 0))))
  }

  const editAmount = (index: number, text: string) => {
    setPeople((list) =>
      list.map((p, i) => ({
        ...p,
        // The first amount typed by hand freezes the others where the equal split had them.
        amountText: i === index ? text : manual ? p.amountText : centsToInput((amounts[i] as number) ?? 0),
      })),
    )
    setManual(true)
  }

  const add = (contact: Contact) => {
    setPicking(false)
    if (people.some((p) => p.contactId === contact.id)) return
    setPeople((list) => [...list, { contactId: contact.id, contactName: contact.name, amountText: '0' }])
  }

  const remove = (contactId: string) => setPeople((list) => list.filter((p) => p.contactId !== contactId))

  const save = async () => {
    if (partCents === null || !partValid) return
    const body: MyShareInput = {
      myShareCents: partCents,
      object: object.trim(),
      notes: notes.trim() === '' ? null : notes,
      shares: people.map((p, i) =>
        manual ? { contactId: p.contactId, amountCents: amounts[i] ?? 0 } : { contactId: p.contactId },
      ),
    }
    setSaving(true)
    try {
      reset(await saveMyShare(transactionId, body))
      notify('Partage enregistré', { type: 'success' })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Le partage n’a pas pu être enregistré.')
    } finally {
      setSaving(false)
    }
  }

  const unshare = async () => {
    setConfirming(false)
    setSaving(true)
    try {
      reset(await removeMyShare(transactionId))
      notify('La dépense n’est plus partagée', { type: 'success' })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Le partage n’a pas pu être retiré.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box data-testid="transaction-share">
      <FormControlLabel
        control={<Switch checked={enabled} disabled={saving} onChange={(_, checked) => toggle(checked)} />}
        label="Dépense partagée"
      />
      <Typography variant="body2" color="text.secondary">
        Seule ta part compte dans tes enveloppes.
      </Typography>

      {enabled && (
        <Box sx={{ mt: 2 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Button
              variant="outlined"
              aria-label="Diminuer ta part"
              disabled={saving || (partCents ?? 0) <= 0}
              onClick={() => step(-STEP_CENTS)}
              sx={{ minWidth: 40 }}
            >
              −
            </Button>
            <TextField
              size="small"
              label="Ta part (€)"
              value={partText}
              disabled={saving}
              onChange={(e) => setPartText(e.target.value)}
              inputProps={{ inputMode: 'decimal' }}
              error={partText !== '' && !partValid}
              helperText={partText !== '' && !partValid ? `Entre 0 et moins de ${formatCents(total)}` : undefined}
            />
            <Button
              variant="outlined"
              aria-label="Augmenter ta part"
              disabled={saving || (partCents ?? 0) >= total - 1}
              onClick={() => step(STEP_CENTS)}
              sx={{ minWidth: 40 }}
            >
              +
            </Button>
          </Stack>

          <Typography sx={{ mt: 1 }} data-testid="owed-to-me">
            On te doit <strong>{owedCents === null ? '—' : formatCents(owedCents)}</strong>
          </Typography>

          <Typography variant="subtitle2" sx={{ mt: 2 }}>
            Qui te doit
          </Typography>
          {people.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Personne pour l’instant.
            </Typography>
          ) : (
            <List dense disablePadding aria-label="Qui te doit">
              {people.map((person, i) => (
                <ListItem
                  key={person.contactId}
                  disableGutters
                  secondaryAction={
                    <IconButton
                      edge="end"
                      aria-label={`Retirer ${person.contactName}`}
                      disabled={saving}
                      onClick={() => remove(person.contactId)}
                    >
                      <CloseIcon fontSize="small" />
                    </IconButton>
                  }
                >
                  <ListItemText primary={person.contactName} sx={{ flex: 1 }} />
                  <TextField
                    size="small"
                    label={`Part de ${person.contactName} (€)`}
                    value={manual ? person.amountText : centsToInput((amounts[i] as number) ?? 0)}
                    disabled={saving}
                    onChange={(e) => editAmount(i, e.target.value)}
                    inputProps={{ inputMode: 'decimal' }}
                    sx={{ width: 160, mr: 5 }}
                  />
                </ListItem>
              ))}
            </List>
          )}
          {sumMismatch && owedCents !== null && (
            <Alert severity="warning" sx={{ mt: 1 }}>
              Les parts font {formatCents(typedSum)}, elles doivent faire {formatCents(owedCents)}.
            </Alert>
          )}
          <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
            <Button onClick={() => setPicking(true)} disabled={saving}>
              Ajouter une personne
            </Button>
            {manual && people.length > 0 && (
              <Button onClick={() => setManual(false)} disabled={saving}>
                Répartir à parts égales
              </Button>
            )}
          </Stack>

          <TextField
            fullWidth
            size="small"
            margin="normal"
            label="Objet"
            value={object}
            disabled={saving}
            onChange={(e) => setObject(e.target.value)}
            inputProps={{ maxLength: 255 }}
          />
          <TextField
            fullWidth
            multiline
            minRows={2}
            margin="normal"
            label="Notes"
            value={notes}
            disabled={saving}
            onChange={(e) => setNotes(e.target.value)}
            inputProps={{ maxLength: 2000 }}
          />

          {error && (
            <Alert severity="error" sx={{ mt: 1 }}>
              {error}
            </Alert>
          )}

          <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 1 }}>
            <Button
              variant="contained"
              onClick={() => void save()}
              disabled={saving || !partValid || people.length === 0 || sumMismatch}
            >
              Enregistrer le partage
            </Button>
            <Button component={Link} to="/contacts">
              Voir tout ce qu’on te doit
            </Button>
          </Stack>
        </Box>
      )}

      {!enabled && error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {error}
        </Alert>
      )}

      <ContactPicker open={picking} title="Qui te doit ?" onClose={() => setPicking(false)} onPick={add} />
      <Confirm
        isOpen={confirming}
        title="Ne plus partager cette dépense ?"
        content="Elle comptera de nouveau en entier dans tes enveloppes, et la créance sera supprimée."
        confirm="Ne plus partager"
        cancel="Annuler"
        onConfirm={() => void unshare()}
        onClose={() => setConfirming(false)}
      />
    </Box>
  )
}
