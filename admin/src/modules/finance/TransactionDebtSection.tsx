import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { Confirm, useNotify } from 'react-admin'
import { Link } from 'react-router-dom'
import { formatCents } from './accountTypes'
import { ContactPicker } from './ContactPicker'
import { fetchContacts } from './contactsApi'
import type { Contact } from './contactsApi'
import {
  centsToInput,
  fetchTransactionDebt,
  parseEuros,
  removeTransactionDebt,
  saveTransactionDebt,
} from './peerDebtsApi'
import type { TransactionDebt, TransactionDebtInput } from './peerDebtsApi'

const STEP_CENTS = 100

type Nature = 'income' | 'debt'

/** The amount to give back when a received transfer first becomes a debt: half, to the cent, at least one. */
const defaultAmount = (totalCents: number): number => Math.max(Math.floor(totalCents / 2), 1)

/**
 * « Ce virement est » on the sheet of a received transfer (MAG-452): a recette,
 * or money to give back — a debt « Tu dois » toward one person. The amount
 * to give back no longer counts in the income; the rest of the transfer does.
 *
 * It writes on its own, through `/api/finance/transactions/{id}/debt`, not
 * with the sheet's « Enregistrer ».
 */
export const TransactionDebtSection = ({ transactionId, label }: { transactionId: string; label: string }) => {
  const notify = useNotify()
  const [debt, setDebt] = useState<TransactionDebt | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [nature, setNature] = useState<Nature>('income')
  const [contact, setContact] = useState<Pick<Contact, 'id' | 'name'> | null>(null)
  const [suggested, setSuggested] = useState<Contact | null>(null)
  const [amountText, setAmountText] = useState('')
  const [object, setObject] = useState('')
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [picking, setPicking] = useState(false)
  const [confirming, setConfirming] = useState(false)

  /** The form as the API last answered it. */
  const reset = useCallback(
    (current: TransactionDebt) => {
      const existing = current.peerDebt
      const share = existing?.shares[0]
      setDebt(current)
      setNature(existing ? 'debt' : 'income')
      setContact(share ? { id: share.contactId, name: share.contactName } : null)
      setAmountText(centsToInput(existing?.amountCents ?? defaultAmount(Math.abs(current.amountCents))))
      setObject(existing?.object ?? label)
      setNotes(existing?.notes ?? '')
      setError(null)
    },
    [label],
  )

  useEffect(() => {
    let live = true
    fetchTransactionDebt(transactionId)
      .then((current) => live && reset(current))
      .catch((e: unknown) => live && setLoadError(e instanceof Error ? e.message : 'Impossible de lire la dette.'))

    return () => {
      live = false
    }
  }, [transactionId, reset])

  // The name of the person the transfer's label points to: only the id comes with the debt.
  const suggestedId = debt?.suggestedContactId ?? null
  useEffect(() => {
    if (!suggestedId) {
      setSuggested(null)
      return
    }
    let live = true
    fetchContacts()
      .then((list) => live && setSuggested(list.find((c) => c.id === suggestedId) ?? null))
      .catch(() => live && setSuggested(null))

    return () => {
      live = false
    }
  }, [suggestedId])

  const total = Math.abs(debt?.amountCents ?? 0)
  const amountCents = parseEuros(amountText)
  const amountValid = amountCents !== null && amountCents >= 1 && amountCents <= total
  const person = contact ?? suggested

  if (loadError) return <Alert severity="error">{loadError}</Alert>
  if (!debt) return <LinearProgress aria-label="Chargement de la dette" />
  if (!debt.canBeDebt) {
    return (
      <Typography variant="body2" color="text.secondary">
        Seul un virement reçu peut être à rembourser.
      </Typography>
    )
  }

  const declared = debt.peerDebt !== null

  const choose = (_: unknown, value: Nature | null) => {
    if (value === null || value === nature) return
    if (value === 'debt') {
      setNature('debt')
      return
    }
    if (declared) {
      setConfirming(true)
      return
    }
    reset(debt)
  }

  const step = (delta: number) => {
    const from = amountCents ?? 0
    setAmountText(centsToInput(Math.min(Math.max(from + delta, 1), Math.max(total, 1))))
  }

  const save = async () => {
    if (!person || amountCents === null || !amountValid) return
    const body: TransactionDebtInput = {
      contactId: person.id,
      amountCents,
      object: object.trim(),
      notes: notes.trim() === '' ? null : notes,
    }
    setSaving(true)
    try {
      reset(await saveTransactionDebt(transactionId, body))
      notify('Dette enregistrée', { type: 'success' })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'La dette n’a pas pu être enregistrée.')
    } finally {
      setSaving(false)
    }
  }

  const unmake = async () => {
    setConfirming(false)
    setSaving(true)
    try {
      reset(await removeTransactionDebt(transactionId))
      notify('Ce virement est de nouveau une recette', { type: 'success' })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'La dette n’a pas pu être retirée.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box data-testid="transaction-debt">
      <Typography variant="subtitle2" id={`debt-nature-${transactionId}`}>
        Ce virement est
      </Typography>
      <ToggleButtonGroup
        exclusive
        size="small"
        color="primary"
        value={nature}
        disabled={saving}
        onChange={choose}
        aria-labelledby={`debt-nature-${transactionId}`}
        sx={{ mt: 1 }}
      >
        <ToggleButton value="income">Une recette</ToggleButton>
        <ToggleButton value="debt">À rembourser</ToggleButton>
      </ToggleButtonGroup>

      {nature === 'income' && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Le virement compte en entier dans tes recettes.
        </Typography>
      )}

      {nature === 'debt' && (
        <Box sx={{ mt: 2 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography data-testid="debt-person">{person ? person.name : 'Personne pour l’instant.'}</Typography>
            <Button onClick={() => setPicking(true)} disabled={saving}>
              {person ? 'Changer de personne' : 'Choisir la personne'}
            </Button>
          </Stack>

          <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 2 }}>
            <Button
              variant="outlined"
              aria-label="Diminuer le montant à rendre"
              disabled={saving || (amountCents ?? 0) <= 1}
              onClick={() => step(-STEP_CENTS)}
              sx={{ minWidth: 40 }}
            >
              −
            </Button>
            <TextField
              size="small"
              label="Montant à rendre (€)"
              value={amountText}
              disabled={saving}
              onChange={(e) => setAmountText(e.target.value)}
              inputProps={{ inputMode: 'decimal' }}
              error={amountText !== '' && !amountValid}
              helperText={amountText !== '' && !amountValid ? `Entre 0,01 € et ${formatCents(total)}` : undefined}
            />
            <Button
              variant="outlined"
              aria-label="Augmenter le montant à rendre"
              disabled={saving || (amountCents ?? 0) >= total}
              onClick={() => step(STEP_CENTS)}
              sx={{ minWidth: 40 }}
            >
              +
            </Button>
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

          {amountValid && person && (
            <Typography variant="body2" color="text.secondary" data-testid="debt-summary">
              Crée une dette de {formatCents(amountCents)} envers {person.name}, dans « Tu dois ».
            </Typography>
          )}
          {amountValid && amountCents < total && (
            <Typography variant="body2" color="text.secondary">
              Les {formatCents(total - amountCents)} restants restent une recette.
            </Typography>
          )}

          {error && (
            <Alert severity="error" sx={{ mt: 1 }}>
              {error}
            </Alert>
          )}

          <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 1 }}>
            <Button variant="contained" onClick={() => void save()} disabled={saving || !person || !amountValid}>
              Enregistrer
            </Button>
            <Button component={Link} to="/contacts">
              Voir tout ce que tu dois
            </Button>
          </Stack>
        </Box>
      )}

      {nature === 'income' && error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {error}
        </Alert>
      )}

      <ContactPicker
        open={picking}
        title="À qui le rendre ?"
        onClose={() => setPicking(false)}
        onPick={(picked) => {
          setPicking(false)
          setContact({ id: picked.id, name: picked.name })
        }}
      />
      <Confirm
        isOpen={confirming}
        title="Ce virement redevient une recette ?"
        content="Ce virement redevient une recette entière, et la dette sera supprimée."
        confirm="Redevenir une recette"
        cancel="Annuler"
        onConfirm={() => void unmake()}
        onClose={() => setConfirming(false)}
      />
    </Box>
  )
}
