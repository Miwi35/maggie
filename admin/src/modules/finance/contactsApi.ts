import { bareId } from './envelopeTree'

/**
 * The people the owner lends to or borrows from (MAG-450), and the labels
 * their transfers show under on the statements.
 */
export interface Contact {
  /** Bare ULID. */
  id: string
  name: string
  notes: string | null
}

/** One label a contact's transfers carry: « VIR. DE TOM MARTIN ». */
export interface ContactCounterparty {
  /** Bare ULID. */
  id: string
  label: string
  transactionCount: number
  /** `Y-m-d`, null when no transfer carries it yet. */
  lastSeenOn: string | null
}

/** `GET /api/finance/contacts/{id}/counterparty-suggestions`: a label that may be theirs. */
export interface CounterpartySuggestion {
  label: string
  transactionCount: number
  lastSeenOn: string | null
}

export const contactIri = (id: string): string => `/api/contacts/${bareId(id)}`

/** The collections are read in one page: an owner knows a few dozen people, not hundreds. */
const ONE_PAGE = 'itemsPerPage=100'

const request = (path: string, init: RequestInit = {}) => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    ...init,
    headers: {
      Accept: 'application/ld+json',
      ...(init.body ? { 'Content-Type': 'application/ld+json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

/** The API's own words when it gives some — « … is already linked to Anna. » —, the fallback otherwise. */
const failure = async (res: Response, fallback: string): Promise<Error> => {
  const body = (await res.json().catch(() => ({}))) as {
    'hydra:description'?: string
    detail?: string
    description?: string
    error?: string
  }

  return new Error(body.detail ?? body['hydra:description'] ?? body.description ?? body.error ?? fallback)
}

const members = async <T>(res: Response): Promise<T[]> => {
  const body = (await res.json()) as { member?: T[] }

  return body.member ?? []
}

export async function fetchContacts(): Promise<Contact[]> {
  const res = await request(`/api/contacts?${ONE_PAGE}`)
  if (!res.ok) throw await failure(res, 'Impossible de lire vos contacts.')

  return (await members<Contact>(res)).map((c) => ({ id: bareId(c.id), name: c.name, notes: c.notes ?? null }))
}

export async function createContact(name: string): Promise<Contact> {
  const res = await request('/api/contacts', { method: 'POST', body: JSON.stringify({ name: name.trim() }) })
  if (!res.ok) throw await failure(res, 'Impossible de créer ce contact.')
  const created = (await res.json()) as Contact

  return { id: bareId(created.id), name: created.name, notes: created.notes ?? null }
}

export async function fetchCounterparties(contactId: string): Promise<ContactCounterparty[]> {
  const contact = encodeURIComponent(contactIri(contactId))
  const res = await request(`/api/contact_counterparties?contact=${contact}&${ONE_PAGE}`)
  if (!res.ok) throw await failure(res, 'Impossible de lire ses comptes.')

  return (await members<ContactCounterparty>(res)).map((c) => ({
    id: bareId(c.id),
    label: c.label,
    transactionCount: c.transactionCount,
    lastSeenOn: c.lastSeenOn,
  }))
}

export async function fetchCounterpartySuggestions(contactId: string): Promise<CounterpartySuggestion[]> {
  const res = await request(`/api/finance/contacts/${bareId(contactId)}/counterparty-suggestions`)
  if (!res.ok) throw await failure(res, 'Impossible de lire les suggestions.')

  return (await res.json()) as CounterpartySuggestion[]
}

/**
 * Links a label to a contact: every transfer it recognises is attached to them.
 * A label already someone else's is refused (400) with the API's message.
 */
export async function linkCounterparty(contactId: string, label: string): Promise<void> {
  const res = await request('/api/contact_counterparties', {
    method: 'POST',
    body: JSON.stringify({ contact: contactIri(contactId), label: label.trim() }),
  })
  if (!res.ok) throw await failure(res, 'Impossible de lier ce libellé.')
}

export async function unlinkCounterparty(counterpartyId: string): Promise<void> {
  const res = await request(`/api/contact_counterparties/${bareId(counterpartyId)}`, { method: 'DELETE' })
  if (!res.ok) throw await failure(res, 'Impossible de retirer ce libellé.')
}

const plural = (n: number, one: string, many: string) => (n > 1 ? many : one)

/** « 1 virement », « 3 virements ». */
export const transferCount = (n: number): string => `${n} ${plural(n, 'virement', 'virements')}`
