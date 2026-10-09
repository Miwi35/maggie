import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import { useNotify, useRecordContext } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { useMercure } from '../../hooks/useMercure'
import { CounterpartLeg, CounterpartSentence, TransferBadge } from './TransferBadge'
import { useTransactionTransfer } from './useTransactionTransfer'
import type { MarkKind, TransferLeg } from './useTransactionTransfer'

const TRANSACTION_TOPICS = ['/api/transactions/{id}']

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

/**
 * The owner's correction of a transfer or rejection marking, on the
 * transaction's own screen: see what the detection decided and which line is
 * the other leg, take the marking off, or put it on by hand and choose the
 * counterpart.
 *
 * Whatever is written here is the owner's decision — the detection never
 * overwrites it, so a correction survives the next catch-up.
 */
export const TransferPanel = () => {
  const record = useRecordContext()
  const notify = useNotify()
  const { transfer, candidates, error, saving, load, loadCandidates, mark, release } = useTransactionTransfer(
    record?.id as string | undefined,
  )
  const [choosing, setChoosing] = useState<MarkKind | null>(null)

  // The other leg can be marked or released from another screen: stay in step.
  useMercure(TRANSACTION_TOPICS, () => {
    void load()
  })

  if (!record) return null

  const kind = transfer?.transferKind
  const amountCents = record.amountCents as number

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
    const releasedKind = kind
    if (await release()) {
      notify(releasedKind === 'rejected' ? 'Ce n’est plus un rejet' : 'Ce n’est plus un virement interne', {
        type: 'info',
      })
    }
  }

  return (
    <Box sx={{ maxWidth: 680, px: 2, pt: 2 }}>
      <FormSection
        first
        title="Virement interne ou rejet"
        description="Un virement entre deux de vos comptes, ou un paiement rejeté par la banque, n’est ni une dépense ni une recette : il ne compte dans aucun chiffre."
      />
      {error && (
        <Alert severity="error" sx={{ mb: 1 }}>
          {error}
        </Alert>
      )}
      {transfer && (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, alignItems: 'flex-start' }}>
          {kind === 'internal' || kind === 'rejected' ? (
            <>
              <Box>
                <TransferBadge transferKind={kind} amountCents={amountCents} />
                <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>
                  {transfer.transferSource === 'manual' ? 'Marqué à la main' : 'Détecté automatiquement'}
                </Typography>
              </Box>
              <Typography variant="body2">
                <CounterpartSentence transfer={transfer} amountCents={amountCents} />
              </Typography>
              <Button color="warning" disabled={saving} onClick={confirmRelease}>
                {kind === 'rejected' ? 'Ce n’est pas un rejet' : 'Ce n’est pas un virement interne'}
              </Button>
            </>
          ) : (
            <>
              <Typography variant="body2" color="text.secondary">
                Cette ligne compte comme une {amountCents > 0 ? 'recette' : 'dépense'} ordinaire.
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
