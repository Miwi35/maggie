import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { useNotify } from 'react-admin'
import { formatCents } from './accountTypes'
import { ContactPicker } from './ContactPicker'
import { centsToInput, fetchAllocation, parseEuros, saveAllocation } from './peerDebtsApi'
import type { AllocationCandidate, AllocationInput, PeerKind, TransactionAllocation } from './peerDebtsApi'
import { MarkdownEditor } from '../../components/markdown/MarkdownEditor'

const STEP_CENTS = 100

const CHOICES: Record<TransactionAllocation['direction'], { value: PeerKind; label: string }[]> = {
  incoming: [
    { value: 'income', label: 'Une recette' },
    { value: 'repayment', label: 'Un remboursement' },
    { value: 'new_debt', label: 'À rembourser' },
  ],
  outgoing: [
    { value: 'repayment', label: 'Rembourse une dette' },
    { value: 'new_claim', label: 'Nouvelle créance' },
    { value: 'other', label: 'Autre' },
  ],
}

/** The amount to give back when a received transfer first becomes a debt: half, to the cent, at least one. */
const defaultDebtAmount = (totalCents: number): number => Math.max(Math.floor(totalCents / 2), 1)

/** The contact's shares first, the API's order otherwise. */
const ordered = (candidates: AllocationCandidate[]): AllocationCandidate[] =>
  [...candidates].sort((a, b) => Number(b.fromContact) - Number(a.fromContact))

type Person = { id: string; name: string }

/**
 * « Ce virement est » (MAG-453): what a transfer with a person is, written on
 * its own through `PUT /api/finance/transactions/{id}/allocation`.
 *
 * Received: « Une recette », « Un remboursement » — the créances it covers,
 * each amount adjustable, the rest « Non affecté » and counted as income — or
 * « À rembourser » (MAG-452). Sent: « Rembourse une dette », « Nouvelle
 * créance » or « Autre ».
 *
 * Takes only the transaction's id, so the sheet, the « À trier » card and the
 * monthly review can all draw it; `onSaved` hears every answer the API accepts.
 */
export const PeerTransferQualifier = ({
  transactionId,
  label = '',
  onSaved,
}: {
  transactionId: string
  /** The transaction's label: the default object of a new dette or créance. */
  label?: string
  onSaved?: (allocation: TransactionAllocation) => void
}) => {
  const notify = useNotify()
  const [allocation, setAllocation] = useState<TransactionAllocation | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [kind, setKind] = useState<PeerKind | null>(null)
  /** The shares checked under « Il couvre », with the amount typed for each. */
  const [covered, setCovered] = useState<Record<string, string>>({})
  const [person, setPerson] = useState<Person | null>(null)
  const [amountText, setAmountText] = useState('')
  const [object, setObject] = useState('')
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [picking, setPicking] = useState(false)

  /** The form as the API last answered it. */
  const reset = useCallback(
    (current: TransactionAllocation) => {
      const created = current.peerDebt
      const share = created?.shares[0]
      setAllocation(current)
      setKind(current.peerKind)
      setCovered(Object.fromEntries(current.allocations.map((a) => [a.shareId, centsToInput(a.amountCents)])))
      setPerson(share ? { id: share.contactId, name: share.contactName } : current.contact)
      setAmountText(centsToInput(created?.amountCents ?? defaultDebtAmount(Math.abs(current.amountCents))))
      setObject(created?.object ?? label)
      setNotes(created?.notes ?? '')
      setError(null)
    },
    [label],
  )

  useEffect(() => {
    let live = true
    fetchAllocation(transactionId)
      .then((current) => live && reset(current))
      .catch((e: unknown) => live && setLoadError(e instanceof Error ? e.message : 'Impossible de lire ce virement.'))

    return () => {
      live = false
    }
  }, [transactionId, reset])

  if (loadError) return <Alert severity="error">{loadError}</Alert>
  if (!allocation) return <LinearProgress aria-label="Chargement du virement" />
  if (!allocation.canBeAllocated) {
    return (
      <Typography variant="body2" color="text.secondary">
        Un virement interne ou un rejet ne se partage pas avec une personne.
      </Typography>
    )
  }

  const incoming = allocation.direction === 'incoming'
  const total = Math.abs(allocation.amountCents)
  const candidates = ordered(allocation.candidates)
  const id = `peer-kind-${allocation.transactionId}`

  // « Il couvre »: each checked share, its amount, and what stays unallocated.
  const parts = candidates
    .filter((c) => c.shareId in covered)
    .map((c) => ({ candidate: c, cents: parseEuros(covered[c.shareId]) }))
  const coveredCents = parts.reduce((sum, p) => sum + (p.cents ?? 0), 0)
  const unallocatedCents = total - coveredCents
  const partValid = (p: (typeof parts)[number]) => p.cents !== null && p.cents >= 1 && p.cents <= p.candidate.availableCents
  const repaymentValid = parts.length > 0 && parts.every(partValid) && unallocatedCents >= 0

  const debtCents = parseEuros(amountText)
  const debtValid = debtCents !== null && debtCents >= 1 && debtCents <= total

  const valid =
    kind === 'repayment'
      ? repaymentValid
      : kind === 'new_debt'
        ? debtValid && person !== null
        : kind === 'new_claim'
          ? person !== null
          : kind !== null

  const toggle = (candidate: AllocationCandidate, checked: boolean) => {
    setCovered((current) => {
      const next = { ...current }
      if (!checked) {
        delete next[candidate.shareId]
        return next
      }
      const others = Object.entries(current).reduce((sum, [, text]) => sum + (parseEuros(text) ?? 0), 0)
      next[candidate.shareId] = centsToInput(Math.max(Math.min(candidate.availableCents, total - others), 0))

      return next
    })
  }

  const stepPart = (candidate: AllocationCandidate, delta: number) => {
    const from = parseEuros(covered[candidate.shareId] ?? '') ?? 0
    const cents = Math.min(Math.max(from + delta, 1), Math.max(candidate.availableCents, 1))
    setCovered((current) => ({ ...current, [candidate.shareId]: centsToInput(cents) }))
  }

  const stepDebt = (delta: number) => {
    setAmountText(centsToInput(Math.min(Math.max((debtCents ?? 0) + delta, 1), Math.max(total, 1))))
  }

  const payload = (): AllocationInput | null => {
    if (!kind || !valid) return null
    const extra = { object: object.trim(), notes: notes.trim() === '' ? null : notes }
    if (kind === 'repayment') {
      return { kind, allocations: parts.map((p) => ({ shareId: p.candidate.shareId, amountCents: p.cents ?? 0 })) }
    }
    if (kind === 'new_debt' && person && debtCents !== null) {
      return { kind, debt: { contactId: person.id, amountCents: debtCents, ...extra } }
    }
    if (kind === 'new_claim' && person) {
      return { kind, claim: { contactId: person.id, ...extra } }
    }

    return { kind }
  }

  const save = async () => {
    const body = payload()
    if (!body) return
    setSaving(true)
    setError(null)
    try {
      const saved = await saveAllocation(transactionId, body)
      reset(saved)
      notify('Virement enregistré', { type: 'success' })
      onSaved?.(saved)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Ce virement n’a pas pu être enregistré.')
    } finally {
      setSaving(false)
    }
  }

  const personLine = (pickLabel: string) => (
    <Stack direction="row" spacing={1} alignItems="center">
      <Typography data-testid="peer-person">{person ? person.name : 'Personne pour l’instant.'}</Typography>
      <Button onClick={() => setPicking(true)} disabled={saving}>
        {person ? 'Changer de personne' : pickLabel}
      </Button>
    </Stack>
  )

  const objectAndNotes = (
    <>
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
      <MarkdownEditor label="Notes" value={notes} disabled={saving} onChange={setNotes} />
    </>
  )

  return (
    <Box data-testid="peer-transfer-qualifier">
      <Typography variant="subtitle2" id={id}>
        Ce virement est
      </Typography>
      <ToggleButtonGroup
        exclusive
        size="small"
        color="primary"
        value={kind}
        disabled={saving}
        onChange={(_, value: PeerKind | null) => value !== null && setKind(value)}
        aria-labelledby={id}
        sx={{ mt: 1, flexWrap: 'wrap' }}
      >
        {CHOICES[allocation.direction].map((choice) => (
          <ToggleButton key={choice.value} value={choice.value}>
            {choice.label}
          </ToggleButton>
        ))}
      </ToggleButtonGroup>

      {kind === 'income' && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Le virement compte en entier dans tes recettes.
        </Typography>
      )}
      {kind === 'other' && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Le virement compte en entier dans tes dépenses.
        </Typography>
      )}

      {kind === 'repayment' && (
        <Box sx={{ mt: 2 }}>
          <Typography variant="subtitle2" id={`${id}-covers`}>
            Il couvre
          </Typography>
          {candidates.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {incoming ? 'Aucune créance en cours.' : 'Aucune dette en cours.'}
            </Typography>
          ) : (
            <Box component="ul" aria-labelledby={`${id}-covers`} sx={{ listStyle: 'none', p: 0, m: 0 }}>
              {candidates.map((candidate) => {
                const checked = candidate.shareId in covered
                const part = parts.find((p) => p.candidate.shareId === candidate.shareId)
                const invalid = part !== undefined && !partValid(part)

                return (
                  <Box component="li" key={candidate.shareId} sx={{ py: 0.5 }}>
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={checked}
                          disabled={saving}
                          onChange={(e) => toggle(candidate, e.target.checked)}
                        />
                      }
                      label={`${candidate.object} · ${candidate.contactName}`}
                    />
                    <Typography variant="body2" color="text.secondary" component="span">
                      reste {formatCents(candidate.remainingCents)} sur {formatCents(candidate.amountCents)}
                    </Typography>
                    {checked && (
                      <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1, ml: 4 }}>
                        <Button
                          variant="outlined"
                          aria-label={`Diminuer le montant pour ${candidate.object}`}
                          disabled={saving || (part?.cents ?? 0) <= 1}
                          onClick={() => stepPart(candidate, -STEP_CENTS)}
                          sx={{ minWidth: 40 }}
                        >
                          −
                        </Button>
                        <TextField
                          size="small"
                          label={`Montant pour ${candidate.object} (€)`}
                          value={covered[candidate.shareId]}
                          disabled={saving}
                          onChange={(e) => setCovered((c) => ({ ...c, [candidate.shareId]: e.target.value }))}
                          inputProps={{ inputMode: 'decimal' }}
                          error={invalid}
                          helperText={invalid ? `Entre 0,01 € et ${formatCents(candidate.availableCents)}` : undefined}
                        />
                        <Button
                          variant="outlined"
                          aria-label={`Augmenter le montant pour ${candidate.object}`}
                          disabled={saving || (part?.cents ?? 0) >= candidate.availableCents}
                          onClick={() => stepPart(candidate, STEP_CENTS)}
                          sx={{ minWidth: 40 }}
                        >
                          +
                        </Button>
                      </Stack>
                    )}
                  </Box>
                )
              })}
            </Box>
          )}
          <Typography variant="body2" sx={{ mt: 1 }} data-testid="peer-unallocated">
            Non affecté : {formatCents(Math.max(unallocatedCents, 0))}
            {incoming ? ', compté dans tes recettes' : ', compté dans tes dépenses'}
          </Typography>
          {unallocatedCents < 0 && (
            <Typography variant="body2" color="error">
              Les montants dépassent le virement de {formatCents(-unallocatedCents)}.
            </Typography>
          )}
        </Box>
      )}

      {kind === 'new_debt' && (
        <Box sx={{ mt: 2 }}>
          {personLine('Choisir la personne')}
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 2 }}>
            <Button
              variant="outlined"
              aria-label="Diminuer le montant à rendre"
              disabled={saving || (debtCents ?? 0) <= 1}
              onClick={() => stepDebt(-STEP_CENTS)}
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
              error={amountText !== '' && !debtValid}
              helperText={amountText !== '' && !debtValid ? `Entre 0,01 € et ${formatCents(total)}` : undefined}
            />
            <Button
              variant="outlined"
              aria-label="Augmenter le montant à rendre"
              disabled={saving || (debtCents ?? 0) >= total}
              onClick={() => stepDebt(STEP_CENTS)}
              sx={{ minWidth: 40 }}
            >
              +
            </Button>
          </Stack>
          {objectAndNotes}
          {debtValid && person && (
            <Typography variant="body2" color="text.secondary" data-testid="peer-summary">
              Crée une dette de {formatCents(debtCents)} envers {person.name}, dans « Tu dois ».
            </Typography>
          )}
          {debtValid && debtCents < total && (
            <Typography variant="body2" color="text.secondary">
              Les {formatCents(total - debtCents)} restants restent une recette.
            </Typography>
          )}
        </Box>
      )}

      {kind === 'new_claim' && (
        <Box sx={{ mt: 2 }}>
          {personLine('Choisir la personne')}
          {objectAndNotes}
          {person && (
            <Typography variant="body2" color="text.secondary" data-testid="peer-summary">
              Crée une créance de {formatCents(total)} : {person.name} te doit {formatCents(total)}.
            </Typography>
          )}
        </Box>
      )}

      {error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {error}
        </Alert>
      )}

      {kind !== null && (
        <Box sx={{ mt: 1 }}>
          <Button variant="contained" onClick={() => void save()} disabled={saving || !valid}>
            Enregistrer
          </Button>
        </Box>
      )}

      <ContactPicker
        open={picking}
        title={kind === 'new_claim' ? 'Qui te doit ?' : 'À qui le rendre ?'}
        onClose={() => setPicking(false)}
        onPick={(picked) => {
          setPicking(false)
          setPerson({ id: picked.id, name: picked.name })
        }}
      />
    </Box>
  )
}
