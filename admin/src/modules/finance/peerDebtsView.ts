import { formatCents } from './accountTypes'
import type { PeerDebtWithHistory } from './peerDebtsApi'

/** One open slice of a créance or dette, seen from one person. */
export interface ContactLine {
  key: string
  object: string
  direction: PeerDebtWithHistory['direction']
  remainingCents: number
}

/** What still waits on one person, per object: their open shares only. */
export function contactLines(debts: PeerDebtWithHistory[], contactId: string): ContactLine[] {
  return debts.flatMap((debt) =>
    debt.status !== 'open'
      ? []
      : debt.shares
          .filter((share) => share.contactId === contactId && share.remainingCents > 0)
          .map((share) => ({
            key: `${debt.id}-${share.id}`,
            object: debt.object,
            direction: debt.direction,
            remainingCents: share.remainingCents,
          })),
  )
}

/** « on te doit 400 € » / « tu dois 280 € ». */
export const owedSentence = (direction: PeerDebtWithHistory['direction'], cents: number): string =>
  direction === 'owed_to_me' ? `on te doit ${formatCents(cents)}` : `tu dois ${formatCents(cents)}`

/** « te doit 200 € » / « tu lui dois 80 € » for a signed net. */
export const netSentence = (netCents: number): string =>
  netCents === 0 ? 'quittes' : netCents > 0 ? `te doit ${formatCents(netCents)}` : `tu lui dois ${formatCents(-netCents)}`

/** « un seul virement de 80 € à Tom solde tout » when the two directions cancel out. */
export const compensationSentence = (name: string, netCents: number): string =>
  `Les deux se compensent : un seul virement de ${formatCents(Math.abs(netCents))} ${netCents < 0 ? 'à' : 'de'} ${name} solde tout`

/** « Julie 200 € · Tom 200 € »: who still owes (or is owed) on one object. */
export const peopleSentence = (debt: PeerDebtWithHistory): string =>
  debt.shares
    .filter((share) => share.remainingCents > 0)
    .map((share) => `${share.contactName} ${formatCents(share.remainingCents)}`)
    .join(' · ')

/** The open créances and dettes, the biggest left to give back first. */
export const openDebts = (debts: PeerDebtWithHistory[]): PeerDebtWithHistory[] =>
  debts.filter((debt) => debt.status === 'open' && debt.remainingCents > 0).sort((a, b) => b.remainingCents - a.remainingCents)
