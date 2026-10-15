import { bareId } from './recurring'

/** One aisle of a store, in the order of the way through it (MAG-493). */
export interface Aisle {
  /** The bare ULID, what `/api/aisles/{id}` routes on. */
  id: string
  name: string
  /** 1 = the first aisle on the way in. */
  position: number
}

/** A product's aisle in one store, as bare ids — what `aisles` holds on `/api/products/{id}`. */
export interface ProductAisle {
  store: string
  aisle: string
}

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

// Every Mercure payload of an aisle — a change, a move, a deletion — goes out on this topic.
export const AISLE_TOPIC = '/api/aisles/{id}'

/** A write the API refused: `message` is its Hydra `detail` when it gave one. */
export class AisleError extends Error {}

const call = (path: string, method: string, body?: unknown) => {
  const token = localStorage.getItem('token')
  const contentType = method === 'PATCH' ? 'application/merge-patch+json' : 'application/ld+json'
  return fetch(`${entrypoint}${path}`, {
    method,
    headers: {
      Accept: 'application/ld+json',
      Authorization: `Bearer ${token}`,
      ...(body !== undefined ? { 'Content-Type': contentType } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })
}

const refused = async (response: Response, fallback: string): Promise<AisleError> => {
  try {
    const body = await response.json()
    const detail = body?.detail ?? body?.description
    return new AisleError(typeof detail === 'string' && detail ? detail : fallback)
  } catch {
    return new AisleError(fallback)
  }
}

interface AisleMember {
  '@id'?: string
  id?: string
  name: string
  position: number
}

const toAisle = (m: AisleMember): Aisle => ({
  id: m.id ?? bareId(m['@id'] ?? ''),
  name: m.name,
  position: m.position,
})

/** The aisles of one store, first aisle first. */
export async function fetchAisles(storeId: string): Promise<Aisle[]> {
  const query = new URLSearchParams({ store: bareId(storeId) })
  const response = await call(`/aisles?${query}`, 'GET')
  if (!response.ok) throw new AisleError(`aisles: ${response.status}`)
  const body = await response.json()
  return ((body?.member ?? []) as AisleMember[]).map(toAisle).sort((a, b) => a.position - b.position)
}

/** Adds an aisle at the end of the store's way. */
export async function createAisle(storeId: string, name: string): Promise<Aisle> {
  const response = await call('/aisles', 'POST', { store: `/api/stores/${bareId(storeId)}`, name })
  if (!response.ok) throw await refused(response, `Le rayon « ${name} » n'a pas été ajouté`)
  return toAisle(await response.json())
}

/** Renames an aisle, or moves it to `position` (1 = first) — the API renumbers the others. */
export async function updateAisle(id: string, patch: { name?: string; position?: number }): Promise<void> {
  const response = await call(`/aisles/${bareId(id)}`, 'PATCH', patch)
  if (!response.ok) throw await refused(response, "Le rayon n'a pas été modifié")
}

/** Deletes an aisle: its products are « Sans rayon » in that store from then on. */
export async function deleteAisle(id: string): Promise<void> {
  const response = await call(`/aisles/${bareId(id)}`, 'DELETE')
  if (!response.ok && response.status !== 404) throw await refused(response, "Le rayon n'a pas été supprimé")
}

/** The aisle of a product (or an ingredient: same table) in each store. */
export async function fetchProductAisles(productId: string): Promise<ProductAisle[]> {
  const response = await call(`/products/${bareId(productId)}`, 'GET')
  if (!response.ok) throw new AisleError(`product: ${response.status}`)
  const body = await response.json()
  return ((body?.aisles ?? []) as ProductAisle[]).map((a) => ({ store: bareId(a.store), aisle: bareId(a.aisle) }))
}

/**
 * The same list with this store's entry replaced: `aisleId` null leaves the
 * product with no aisle there. The other stores are kept as they are, because
 * the API replaces the whole list.
 */
export const withAisleIn = (aisles: ProductAisle[], storeId: string, aisleId: string | null): ProductAisle[] => {
  const store = bareId(storeId)
  const others = aisles.filter((a) => bareId(a.store) !== store)
  return aisleId ? [...others, { store, aisle: bareId(aisleId) }] : others
}

/** Puts a product in `aisleId` in one store (null = « Sans rayon »), its other stores untouched. */
export async function setProductAisle(productId: string, storeId: string, aisleId: string | null): Promise<void> {
  const current = await fetchProductAisles(productId)
  const response = await call(`/products/${bareId(productId)}`, 'PATCH', {
    aisles: withAisleIn(current, storeId, aisleId),
  })
  if (!response.ok) throw await refused(response, "Le rayon n'a pas été enregistré")
}
