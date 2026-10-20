import { apiUrl, headers } from './taskViews'

/** `GET /api/stars?month=`: the constellation of a month (MAG-509). `best` is the best month before it. */
export interface Stars {
  month: string
  count: number
  total: number
  best: { month: string; count: number } | null
  latestAt: string | null
}

/** The month asked for, or the current one in the user's timezone when none is given. */
export async function fetchStars(month?: string): Promise<Stars> {
  const query = month ? `?month=${month}` : ''
  const response = await fetch(`${apiUrl}/stars${query}`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) throw new Error(`Étoiles : ${response.status}`)

  return (await response.json()) as Stars
}

/** `YYYY-MM` moved by `n` months (negative goes back). */
export function shiftMonth(month: string, n: number): string {
  const [year, number] = month.split('-').map(Number)
  const index = year * 12 + (number - 1) + n

  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}

const NAMES = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre']

/** « d'octobre », « de novembre »: the month as the title of its constellation reads it. */
export function ofMonth(month: string): string {
  const name = NAMES[Number(month.split('-')[1]) - 1]

  return /^[aeiouéh]/.test(name) ? `d'${name}` : `de ${name}`
}
