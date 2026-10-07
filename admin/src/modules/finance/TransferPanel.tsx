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
import { CounterpartLeg, TransferBadge } from './TransferBadge'
import { useTransactionTransfer } from './useTransactionTransfer'
import type { TransferLeg } from './useTransactionTransfer'

const TRANSACTION_TOPICS = ['/api/transactions/{id}']

const NO_COUNTERPART = ''

const CounterpartChoice = ({
  onClose,
  onConfirm,
  saving,
  candidates,
}: {
  onClose: () => void
  onConfirm: (counterpartId?: string) => void
  saving: boolean
  candidates: TransferLeg[] | null
}) => {
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
              {candidates.length === 0
                ? 'Aucune ligne d’un autre de vos comptes ne correspond (montant opposé, dans les quinze jours).'
                : 'Les lignes d’un autre de vos comptes, au montant opposé, à quinze jours près.'}
            </Typography>
            <RadioGroup value={choice} onChange={(_, value) => setChoice(value)}>
              {candidates.map((leg) => (
                <FormControlLabel key={leg.id} value={leg.id} control={<Radio />} label={<CounterpartLeg leg={leg} />} />
              ))}
              <FormControlLabel
                value={NO_COUNTERPART}
                control={<Radio />}
                label="Aucune contrepartie : l’autre compte n’est pas suivi ici"
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
          Marquer comme virement interne
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/**
 * The owner's correction of a transfer marking, on the transaction's own
 * screen: see what the detection decided and which line is the other leg,
 * take the marking off, or put it on by hand and choose the counterpart.
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
  const [choosing, setChoosing] = useState(false)

  // The other leg can be marked or released from another screen: stay in step.
  useMercure(TRANSACTION_TOPICS, () => {
    void load()
  })

  if (!record) return null

  const isInternal = transfer?.transferKind === 'internal'

  const startMarking = () => {
    setChoosing(true)
    void loadCandidates()
  }

  const confirmMarking = async (counterpartId?: string) => {
    if (await mark(counterpartId)) {
      setChoosing(false)
      notify('Marqué comme virement interne', { type: 'info' })
    }
  }

  const confirmRelease = async () => {
    if (await release()) {
      notify('Ce n’est plus un virement interne', { type: 'info' })
    }
  }

  return (
    <Box sx={{ maxWidth: 680, px: 2, pt: 2 }}>
      <FormSection
        first
        title="Virement interne"
        description="Un virement entre deux de vos comptes n’est ni une dépense ni une recette : il ne compte dans aucun chiffre."
      />
      {error && (
        <Alert severity="error" sx={{ mb: 1 }}>
          {error}
        </Alert>
      )}
      {transfer && (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, alignItems: 'flex-start' }}>
          {isInternal ? (
            <>
              <Box>
                <TransferBadge transferKind={transfer.transferKind} />
                <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>
                  {transfer.transferSource === 'manual' ? 'Marqué à la main' : 'Détecté automatiquement'}
                </Typography>
              </Box>
              <Typography variant="body2">
                {transfer.counterpart ? (
                  <>
                    Contrepartie : <CounterpartLeg leg={transfer.counterpart} />
                  </>
                ) : (
                  'Sans contrepartie : l’autre compte n’est pas suivi.'
                )}
              </Typography>
              <Button color="warning" disabled={saving} onClick={confirmRelease}>
                Ce n’est pas un virement interne
              </Button>
            </>
          ) : (
            <>
              <Typography variant="body2" color="text.secondary">
                Cette ligne compte comme une {(record.amountCents as number) > 0 ? 'recette' : 'dépense'} ordinaire.
              </Typography>
              <Button disabled={saving} onClick={startMarking}>
                C’est un virement interne
              </Button>
            </>
          )}
        </Box>
      )}
      {choosing && (
        <CounterpartChoice
          candidates={candidates}
          saving={saving}
          onClose={() => setChoosing(false)}
          onConfirm={confirmMarking}
        />
      )}
    </Box>
  )
}
