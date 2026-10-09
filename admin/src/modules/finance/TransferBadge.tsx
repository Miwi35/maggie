import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import UndoIcon from '@mui/icons-material/Undo'
import { Amount } from './AmountField'
import { useTransactionTransfer } from './useTransactionTransfer'
import type { TransferLeg, TransferState } from './useTransactionTransfer'

export const TRANSFER_BADGE_LABEL = 'Virement interne'
/** The payment the bank refused, and the credit that gave it back. */
export const REJECTED_DEBIT_LABEL = 'Rejeté'
export const REJECTED_CREDIT_LABEL = 'Rejet'

const asDay = (iso: string) => iso.split('-').reverse().join('/')

/**
 * Marks a line that counts in no figure: money moved between two of the
 * owner's own accounts, or a payment the bank rejected — « Rejeté » on the
 * debit, « Rejet » on the credit that gave it back.
 */
export const TransferBadge = ({
  transferKind,
  amountCents,
}: {
  transferKind?: string | null
  amountCents?: number | null
}) => {
  if (transferKind === 'internal') {
    return (
      <Chip
        size="small"
        color="info"
        variant="outlined"
        icon={<SwapHorizIcon />}
        label={TRANSFER_BADGE_LABEL}
        sx={{ ml: 1 }}
      />
    )
  }
  if (transferKind === 'rejected') {
    return (
      <Chip
        size="small"
        color="warning"
        variant="outlined"
        icon={<UndoIcon />}
        label={(amountCents ?? 0) > 0 ? REJECTED_CREDIT_LABEL : REJECTED_DEBIT_LABEL}
        sx={{ ml: 1 }}
      />
    )
  }

  return null
}

/** « Livret · 13/09/2026 · Virement du Livret » — enough to recognise the other leg. */
const describeLeg = (leg: TransferLeg) => `${leg.accountName} · ${asDay(leg.bookedAt)} · ${leg.label}`

export const CounterpartLeg = ({ leg }: { leg: TransferLeg }) => (
  <Box component="span" sx={{ display: 'inline-flex', gap: 1, alignItems: 'baseline' }}>
    <span>{describeLeg(leg)}</span>
    <Amount cents={leg.amountCents} currency={leg.currency} signed />
  </Box>
)

/**
 * The sentence that names the other leg. A rejection pairs two lines of the
 * same account: the credit says what it gives back, the debit who gave it
 * back. `amountCents` is the line's own amount, which says which leg it is.
 */
export const CounterpartSentence = ({ transfer, amountCents }: { transfer: TransferState; amountCents?: number }) => {
  const { counterpart } = transfer

  if (transfer.transferKind === 'rejected') {
    if (!counterpart) return <>Sans contrepartie</>
    const isCredit = amountCents !== undefined ? amountCents > 0 : counterpart.amountCents < 0

    return (
      <>
        {isCredit ? 'Rejet de : ' : 'Rendu par : '}
        <CounterpartLeg leg={counterpart} />
      </>
    )
  }

  return counterpart ? (
    <>
      Contrepartie : <CounterpartLeg leg={counterpart} />
    </>
  ) : (
    <>Sans contrepartie : l’autre compte n’est pas suivi</>
  )
}

/**
 * The other leg of a marked line, read from the API: the list only carries
 * its IRI, and its label and account are what let the owner check the pair.
 */
export const TransferCounterpart = ({ transactionId, amountCents }: { transactionId: string; amountCents?: number }) => {
  const { transfer } = useTransactionTransfer(transactionId)

  if (transfer === null || transfer.transferKind === 'none') return null

  return (
    <Box sx={{ typography: 'caption', color: 'text.secondary' }}>
      <CounterpartSentence transfer={transfer} amountCents={amountCents} />
    </Box>
  )
}
