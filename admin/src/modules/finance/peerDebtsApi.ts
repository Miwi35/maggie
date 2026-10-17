import { formatCents } from './accountTypes'
import { bareId } from './envelopeTree'

/**
 * « Ma part d'une dépense partagée » (MAG-451): the part of a spend the owner
 * kept, and the créance — a PeerDebt — the rest becomes, split between people.
 */

/** One person's slice of a créance. */
export interface PeerDebtShare {
  id: string
  contactId: string
  contactName: string
  amountCents: number
  repaidCents: number
  remainingCents: number
}

export interface PeerDebt {
  id: string
  /** `owed_to_me`: they owe the owner; `i_owe`: the owner owes them. */
  direction: 'owed_to_me' | 'i_owe'
  object: string
  notes: string | null
  originTransactionId: string | null
  amountCents: number
  repaidCents: number
  remainingCents: number
  status: 'open' | 'settled'
  createdAt: string
  shares: PeerDebtShare[]
}

/** `GET|PUT|DELETE /api/finance/transactions/{id}/my-share`. */
export interface MyShare {
  transactionId: string
  amountCents: number
  /** Null while the spend is not shared. */
  myShareCents: number | null
  /** Only a spend can be shared: neither an income nor a transfer. */
  canBeShared: boolean
  peerDebt: PeerDebt | null
}

/** One person in the PUT: `amountCents` omitted on every share splits the rest equally. */
export interface MyShareShareInput {
  contactId: string
  amountCents?: number
}

export interface MyShareInput {
  myShareCents: number
  /** Empty or omitted: the transaction's label. */
  object?: string
  notes?: string | null
  shares: MyShareShareInput[]
}

const request = (path: string, init: RequestInit = {}, accept = 'application/json') => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    ...init,
    headers: {
      Accept: accept,
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

/** The API's own words — « La part de Tom… » —, the fallback otherwise. */
const failure = async (res: Response, fallback: string): Promise<Error> => {
  const body = (await res.json().catch(() => ({}))) as { error?: string; detail?: string }

  return new Error(body.error ?? body.detail ?? fallback)
}

const myShareUrl = (transactionId: string) => `/api/finance/transactions/${bareId(transactionId)}/my-share`

export async function fetchMyShare(transactionId: string): Promise<MyShare> {
  const res = await request(myShareUrl(transactionId))
  if (!res.ok) throw await failure(res, 'Impossible de lire le partage de cette dépense.')

  return (await res.json()) as MyShare
}

export async function saveMyShare(transactionId: string, input: MyShareInput): Promise<MyShare> {
  const res = await request(myShareUrl(transactionId), { method: 'PUT', body: JSON.stringify(input) })
  if (!res.ok) throw await failure(res, 'Le partage n’a pas pu être enregistré.')

  return (await res.json()) as MyShare
}

export async function removeMyShare(transactionId: string): Promise<MyShare> {
  const res = await request(myShareUrl(transactionId), { method: 'DELETE' })
  if (!res.ok) throw await failure(res, 'Le partage n’a pas pu être retiré.')

  return (await res.json()) as MyShare
}

/** `GET|POST|DELETE /api/finance/transactions/{id}/debt` (MAG-452): a received transfer to give back. */
export interface TransactionDebt {
  transactionId: string
  amountCents: number
  /** Only a received transfer — neither internal nor a rejection — can be a debt. */
  canBeDebt: boolean
  /** Bare id of the person whose label the transfer carries, when one is known. */
  suggestedContactId: string | null
  /** The `i_owe` créance the transfer declares, with one share. */
  peerDebt: PeerDebt | null
}

export interface TransactionDebtInput {
  /** Bare id, not the IRI. */
  contactId: string
  amountCents: number
  /** Empty or omitted: the transaction's label. */
  object?: string
  notes?: string | null
}

const debtUrl = (transactionId: string) => `/api/finance/transactions/${bareId(transactionId)}/debt`

export async function fetchTransactionDebt(transactionId: string): Promise<TransactionDebt> {
  const res = await request(debtUrl(transactionId))
  if (!res.ok) throw await failure(res, 'Impossible de lire la dette de ce virement.')

  return (await res.json()) as TransactionDebt
}

export async function saveTransactionDebt(transactionId: string, input: TransactionDebtInput): Promise<TransactionDebt> {
  const res = await request(debtUrl(transactionId), { method: 'POST', body: JSON.stringify(input) })
  if (!res.ok) throw await failure(res, 'La dette n’a pas pu être enregistrée.')

  return (await res.json()) as TransactionDebt
}

export async function removeTransactionDebt(transactionId: string): Promise<TransactionDebt> {
  const res = await request(debtUrl(transactionId), { method: 'DELETE' })
  if (!res.ok) throw await failure(res, 'La dette n’a pas pu être retirée.')

  return (await res.json()) as TransactionDebt
}

/** Every créance of the owner, newest first, in one page. */
export async function fetchPeerDebts(): Promise<PeerDebt[]> {
  const res = await request('/api/peer_debts', {}, 'application/ld+json')
  if (!res.ok) throw await failure(res, 'Impossible de lire les créances.')
  const body = (await res.json()) as { member?: PeerDebt[] }

  return body.member ?? []
}

/**
 * `total` split between `count` people the way the API does it: equal parts
 * in cents, the remainder on the last one. 800 € − 200 € between three: 200 € each.
 */
export function splitEqually(totalCents: number, count: number): number[] {
  if (count <= 0) return []
  const base = Math.floor(totalCents / count)

  return Array.from({ length: count }, (_, i) => (i === count - 1 ? totalCents - base * (count - 1) : base))
}

/** « 200 », « 12,5 », « 12.50 € » → cents; null when it is not an amount. */
export function parseEuros(text: string): number | null {
  const cleaned = text.replace(/[\s€]/gu, '').replace(',', '.')
  if (cleaned === '' || !/^\d+(\.\d{0,2})?$/.test(cleaned)) return null

  return Math.round(Number(cleaned) * 100)
}

/** « te doit 200,00 € » or « tu lui dois 50,00 € »: what is left of one person's share. */
export const debtSentence = (direction: PeerDebt['direction'], remainingCents: number): string =>
  direction === 'owed_to_me' ? `te doit ${formatCents(remainingCents)}` : `tu lui dois ${formatCents(remainingCents)}`

/** Cents → what an amount field shows: « 200 », « 12,50 ». */
export function centsToInput(cents: number): string {
  return cents % 100 === 0 ? String(cents / 100) : (cents / 100).toFixed(2).replace('.', ',')
}
