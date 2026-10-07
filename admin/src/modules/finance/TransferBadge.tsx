import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import { Amount } from './AmountField'
import { useTransactionTransfer } from './useTransactionTransfer'
import type { TransferLeg } from './useTransactionTransfer'

export const TRANSFER_BADGE_LABEL = 'Virement interne'

const asDay = (iso: string) => iso.split('-').reverse().join('/')

/** Marks a line that moved money between two of the owner's own accounts. */
export const TransferBadge = ({ transferKind }: { transferKind?: string | null }) =>
  transferKind === 'internal' ? (
    <Chip
      size="small"
      color="info"
      variant="outlined"
      icon={<SwapHorizIcon />}
      label={TRANSFER_BADGE_LABEL}
      sx={{ ml: 1 }}
    />
  ) : null

/** « Livret · 13/09/2026 · Virement du Livret » — enough to recognise the other leg. */
const describeLeg = (leg: TransferLeg) => `${leg.accountName} · ${asDay(leg.bookedAt)} · ${leg.label}`

export const CounterpartLeg = ({ leg }: { leg: TransferLeg }) => (
  <Box component="span" sx={{ display: 'inline-flex', gap: 1, alignItems: 'baseline' }}>
    <span>{describeLeg(leg)}</span>
    <Amount cents={leg.amountCents} currency={leg.currency} signed />
  </Box>
)

/**
 * The other leg of a marked line, read from the API: the list only carries
 * its IRI, and its label and account are what let the owner check the pair.
 */
export const TransferCounterpart = ({ transactionId }: { transactionId: string }) => {
  const { transfer } = useTransactionTransfer(transactionId)

  if (transfer === null) return null

  return (
    <Box sx={{ typography: 'caption', color: 'text.secondary' }}>
      {transfer.counterpart ? (
        <>
          Contrepartie : <CounterpartLeg leg={transfer.counterpart} />
        </>
      ) : (
        'Sans contrepartie : l’autre compte n’est pas suivi'
      )}
    </Box>
  )
}
