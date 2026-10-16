import { bareId } from './recurring'

/** One product the owner buys regularly and is due again (`GET /api/grocery-suggestions`, MAG-495). */
export interface PurchaseSuggestion {
  product: { id: string; name: string }
  everyDays: number
  lastBoughtOn: string
  dueOn: string
  usualQuantity: number | null
  usualUnit: string | null
}

/** The rhythm read from the days between two purchases — the mobile app spells it the same way. */
export const rhythmLabel = (everyDays: number): string => {
  const d = Math.round(everyDays)
  if (d <= 1) return 'tous les jours'
  if (d % 7 === 0 && d < 63) {
    const w = d / 7
    return w === 1 ? 'toutes les sem.' : `toutes les ${w} sem.`
  }
  if (d < 28) return `tous les ${d} j`
  const m = Math.max(1, Math.round(d / 30))
  return m === 1 ? 'tous les mois' : `tous les ${m} mois`
}

/** What « Ajouter » sends to `/api/grocery/add-item`: an ordinary manual line, at the usual quantity. */
export const suggestionItem = (suggestion: PurchaseSuggestion): Record<string, unknown> => ({
  label: suggestion.product.name,
  ...(suggestion.usualQuantity != null && { quantity: suggestion.usualQuantity }),
  ...(suggestion.usualUnit != null && { unit: suggestion.usualUnit }),
})

const isSuggestion = (value: unknown): value is PurchaseSuggestion => {
  const s = value as PurchaseSuggestion | null
  return !!s && typeof s.product?.id === 'string' && typeof s.product?.name === 'string' && typeof s.everyDays === 'number'
}

/** The API answers a bare JSON array; anything else is read as « nothing to suggest ». */
export const parseSuggestions = (body: unknown): PurchaseSuggestion[] =>
  Array.isArray(body) ? body.filter(isSuggestion) : []

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

const authorized = (init: RequestInit = {}): RequestInit => ({
  ...init,
  headers: { Accept: 'application/json', Authorization: `Bearer ${localStorage.getItem('token')}` },
})

export async function fetchSuggestions(): Promise<PurchaseSuggestion[]> {
  const response = await fetch(`${entrypoint}/grocery-suggestions`, authorized())
  if (!response.ok) throw new Error(`grocery suggestions: ${response.status}`)
  return parseSuggestions(await response.json())
}

/** « Pas besoin »: the product leaves the suggestions until it is due again. */
export const snoozeSuggestion = (productId: string) =>
  fetch(`${entrypoint}/grocery-suggestions/${bareId(productId)}/snooze`, authorized({ method: 'POST' }))

/** Updates on these topics change what is due: a product bought, snoozed or edited, an errand ended. */
export const PRODUCT_TOPIC = '/api/products/{id}'
export const ERRAND_TOPIC = '/api/errands/{id}'

const SUGGESTION_ONLY_PATHS = ['/api/products/', '/api/errands/']

/** A product's or an errand's update: it changes the suggestions, not the list on screen. */
export const isSuggestionOnlyUpdate = (data?: string): boolean => {
  if (!data) return false
  try {
    const id = JSON.parse(data)?.['@id']
    return typeof id === 'string' && SUGGESTION_ONLY_PATHS.some((path) => id.startsWith(path))
  } catch {
    return false
  }
}
