import { addDays, parseDay } from '../../dates'

export interface QuickAdd {
  title: string
  /** `YYYY-MM-DD`; absent when the text names no day. */
  dueDate?: string
}

const WEEKDAYS = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi']

const normalise = (word: string): string =>
  word
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[’]/g, "'")

/** The day a closing word names, from `today`; a weekday is its next occurrence, never today. */
const dayOf = (word: string, today: string): string | null => {
  const token = normalise(word)
  if (token === "aujourd'hui") return today
  if (token === 'demain') return addDays(today, 1)
  if (token === 'apres-demain') return addDays(today, 2)
  const weekday = WEEKDAYS.indexOf(token)
  if (weekday === -1) return null

  return addDays(today, (weekday - parseDay(today).getDay() + 7) % 7 || 7)
}

const LINKS = new Set(['pour', 'le', 'ce', 'avant'])

/**
 * « Appeler le garage demain » (MAG-644): a closing day word — aujourd'hui, demain, après-demain
 * or a weekday — sets the due day and leaves the title. Any other text is the title as typed.
 */
export function parseQuickAdd(input: string, today: string): QuickAdd {
  const text = input.trim().replace(/\s+/g, ' ')
  const words = text.split(' ')
  const last = words[words.length - 1]
  const dueDate = words.length > 1 ? dayOf(last, today) : null
  if (!dueDate) return { title: text }

  const rest = words.slice(0, -1)
  if (rest.length > 1 && LINKS.has(normalise(rest[rest.length - 1]))) rest.pop()

  return { title: rest.join(' '), dueDate }
}
