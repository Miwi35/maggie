export type Frequency = 'weekly' | 'biweekly' | 'monthly'

export interface RecurringItem {
  /** The bare ULID, what `/api/recurring_grocery_items/{id}` routes on. */
  id: string
  /** The bare ULID of the product; null for a label-only item. */
  product: string | null
  label: string
  frequency: Frequency
  nextDueAt: string | null
}

export const FREQUENCY_CHOICES: { id: Frequency; name: string }[] = [
  { id: 'weekly', name: 'Toutes les semaines' },
  { id: 'biweekly', name: 'Toutes les 2 semaines' },
  { id: 'monthly', name: 'Tous les mois' },
]

export const frequencyName = (frequency: Frequency): string =>
  FREQUENCY_CHOICES.find((choice) => choice.id === frequency)?.name ?? frequency

// The Hydra data provider puts an IRI (`/api/products/01M3…`) where the API wants the bare ULID.
export const bareId = (id: string) => id.split('/').pop() ?? id

const RECURRING_PATH = '/api/recurring_grocery_items/'

// Every Mercure payload carries its topic as `@id`: that is what tells a recurring item's update from the list's.
export const isRecurringUpdate = (data?: string): boolean => {
  if (!data) return false
  try {
    const id = JSON.parse(data)?.['@id']
    return typeof id === 'string' && id.startsWith(RECURRING_PATH)
  } catch {
    return false
  }
}

export const RECURRING_TOPIC = `${RECURRING_PATH}{id}`
