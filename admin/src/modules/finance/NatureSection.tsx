import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import { useNotify } from 'react-admin'
import { CounterpartLeg, CounterpartSentence, TransferBadge } from './TransferBadge'
import { NatureInput } from './TransactionForm'
import { isBetweenAccounts } from './useTransactionTransfer'
import type { MarkKind, TransactionTransfer, TransferLeg } from './useTransactionTransfer'

const NO_COUNTERPART = ''

/** What the counterpart dialog says, by marking: an internal transfer looks at the other accounts, a rejection at this one. */
const CHOICE_TEXT: Record<MarkKind, { found: string; none: string; noCounterpart: string; confirm: string }> = {
  internal: {
    found: 'Les lignes d’un autre de vos comptes, au montant opposé, à quinze jours près.',
    none: 'Aucune ligne d’un autre de vos comptes ne correspond (montant opposé, dans les quinze jours).',
    noCounterpart: 'Aucune contrepartie : l’autre compte n’est pas suivi ici',
    confirm: 'Marquer comme virement interne',
  },
  rejected: {
    found: 'Les lignes du même compte, au montant opposé, à quinze jours près.',
    none: 'Aucune ligne du même compte ne correspond (montant opposé, dans les quinze jours).',
    noCounterpart: 'Aucune contrepartie : le paiement rejeté n’est pas ici',
    confirm: 'Marquer comme rejet',
  },
}

const MARKED_NOTICE: Record<MarkKind, string> = {
  internal: 'Marqué comme virement interne',
  rejected: 'Marqué comme rejet',
}

const CounterpartChoice = ({
  kind,
  onClose,
  onConfirm,
  saving,
  candidates,
}: {
  kind: MarkKind
  onClose: () => void
  onConfirm: (counterpartId?: string) => void
  saving: boolean
  candidates: TransferLeg[] | null
}) => {
  const text = CHOICE_TEXT[kind]
  const [choice, setChoice] = useState(NO_COUNTERPART)

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="transfer-choice-title">
      <DialogTitle id="transfer-choice-title">Choisir la contrepartie</DialogTitle>
      <DialogContent>
        {candidates === null ? (
          <Typography color="text.secondary">Recherche des contreparties possibles…</Typography>
        ) : (
          <>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              {candidates.length === 0 ? text.none : text.found}
            </Typography>
            <RadioGroup value={choice} onChange={(_, value) => setChoice(value)}>
              {candidates.map((leg) => (
                <FormControlLabel key={leg.id} value={leg.id} control={<Radio />} label={<CounterpartLeg leg={leg} />} />
              ))}
              <FormControlLabel
                value={NO_COUNTERPART}
                control={<Radio />}
                label={text.noCounterpart}
              />
            </RadioGroup>
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button
          variant="contained"
          disabled={candidates === null || saving}
          onClick={() => onConfirm(choice === NO_COUNTERPART ? undefined : choice)}
        >
          {text.confirm}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

export const REGULARISED_LABEL = 'Régularisé'
export const NOT_REGULARISED_LABEL = 'Non régularisé'

/**
 * The « Nature » section of the sheet: what the line is. An ordinary line is
 * a dépense or a recette; a transfer between the owner's accounts or a
 * rejected payment counts in no figure, and the owner can take that marking
 * off, or put it on by hand and choose the other leg.
 *
 * Whatever is written here is the owner's decision — the detection never
 * overwrites it, so a correction survives the next catch-up. The gestures act
 * at once: they are not part of what « Enregistrer » sends.
 */
export const NatureSection = ({
  transferApi,
  kind,
  amountCents,
}: {
  transferApi: TransactionTransfer
  /** What the sheet currently takes the line for: the read marking, the record's own until it lands. */
  kind: string
  amountCents: number
}) => {
  const notify = useNotify()
  const { transfer, candidates, error, saving, loadCandidates, mark, release } = transferApi
  const [choosing, setChoosing] = useState<MarkKind | null>(null)

  const marked = isBetweenAccounts(kind) || kind === 'rejected'

  const startMarking = (markKind: MarkKind) => {
    setChoosing(markKind)
    void loadCandidates(markKind)
  }

  const confirmMarking = async (counterpartId?: string) => {
    if (choosing === null) return
    const markKind = choosing
    if (await mark(counterpartId, markKind)) {
      setChoosing(null)
      notify(MARKED_NOTICE[markKind], { type: 'info' })
    }
  }

  const confirmRelease = async () => {
    if (await release()) {
      notify(kind === 'rejected' ? 'Ce n’est plus un rejet' : 'Ce n’est plus un virement interne', {
        type: 'info',
      })
    }
  }

  return (
    <Box>
      {error && (
        <Alert severity="error" sx={{ mb: 1 }}>
          {error}
        </Alert>
      )}
      {!marked && <NatureInput />}
      {transfer && (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, alignItems: 'flex-start' }}>
          {marked ? (
            <>
              <Box>
                <TransferBadge transferKind={kind} amountCents={amountCents} />
                <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>
                  {transfer.transferSource === 'manual' ? 'Marqué à la main' : 'Détecté automatiquement'}
                </Typography>
              </Box>
              {kind === 'rejected' && (
                <Chip
                  size="small"
                  color={transfer.counterpart ? 'success' : 'default'}
                  label={transfer.counterpart ? REGULARISED_LABEL : NOT_REGULARISED_LABEL}
                />
              )}
              <Typography variant="body2">
                <CounterpartSentence transfer={transfer} amountCents={amountCents} />
              </Typography>
              <Typography variant="caption" color="text.secondary">
                Cette ligne ne compte dans aucun chiffre : ni dépense, ni recette.
              </Typography>
              <Button color="warning" disabled={saving} onClick={confirmRelease}>
                {kind === 'rejected' ? 'Ce n’est pas un rejet' : 'Ce n’est pas un virement interne'}
              </Button>
            </>
          ) : (
            <>
              <Typography variant="body2" color="text.secondary">
                Un virement entre deux de vos comptes, ou un paiement rejeté par la banque, n’est ni une dépense ni une
                recette : il ne compte dans aucun chiffre.
              </Typography>
              <Box sx={{ display: 'flex', gap: 1 }}>
                <Button disabled={saving} onClick={() => startMarking('internal')}>
                  C’est un virement interne
                </Button>
                <Button disabled={saving} onClick={() => startMarking('rejected')}>
                  C’est un rejet
                </Button>
              </Box>
            </>
          )}
        </Box>
      )}
      {choosing && (
        <CounterpartChoice
          kind={choosing}
          candidates={candidates}
          saving={saving}
          onClose={() => setChoosing(null)}
          onConfirm={confirmMarking}
        />
      )}
    </Box>
  )
}
