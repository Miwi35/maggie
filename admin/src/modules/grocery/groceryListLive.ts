import { RECURRING_TOPIC } from './recurring'
import { ERRAND_TOPIC, PRODUCT_TOPIC } from './purchaseSuggestions'

// One stream for all: the page already holds five, and a browser allows six per origin.
// Products and errands are there for « Souvent pris » only (MAG-495). Whoever follows the list
// asks for these very topics, so the browser keeps a single connection for all of them.
export const GROCERY_LIST_TOPICS = ['/api/grocery_lists/{id}', RECURRING_TOPIC, PRODUCT_TOPIC, ERRAND_TOPIC]

// The REST API sends `buyAfter` as a date-time, Mercure as a plain date: the
// first ten characters are the day in both, compared with the local today.
export const localToday = () => {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export const isDeferred = (item: { buyAfter?: string | null }, today: string) => !!item.buyAfter && item.buyAfter.slice(0, 10) > today
