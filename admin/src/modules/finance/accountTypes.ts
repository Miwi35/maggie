export const ACCOUNT_TYPE_CHOICES = [
  { id: 'checking', name: 'Compte courant' },
  { id: 'savings', name: 'Épargne' },
  { id: 'investment', name: 'Investissement' },
  { id: 'cash', name: 'Espèces' },
]

export const ACCOUNT_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  ACCOUNT_TYPE_CHOICES.map((c) => [c.id, c.name]),
)

const THIN_NBSP = '\u202F'
const NBSP = '\u00A0'
const MINUS = '\u2212'
const DEFAULT_SYMBOL = '€'

const numbers = [0, 2].map((digits) => new Intl.NumberFormat('fr-FR', { minimumFractionDigits: digits, maximumFractionDigits: digits }))

/** The bank may send « XXX » (no currency, MAG-376) or nothing: the symbol falls back to « € », the code is never shown. */
function currencySymbol(currency?: string | null): string {
  const code = (currency ?? '').trim().toUpperCase()
  if (code === '' || code === 'EUR' || code === 'XXX') return DEFAULT_SYMBOL
  try {
    const parts = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: code }).formatToParts(0)
    return parts.find((part) => part.type === 'currency')?.value ?? DEFAULT_SYMBOL
  } catch {
    return DEFAULT_SYMBOL
  }
}

function money(amount: number, negative: boolean, decimals: 0 | 2, currency?: string | null): string {
  // The whitespace of fr-FR moves between ICU builds: spell it here, the same on every runtime.
  const digits = numbers[decimals === 0 ? 0 : 1].format(amount).replace(/\s/gu, THIN_NBSP)

  return `${negative ? MINUS : ''}${digits}${NBSP}${currencySymbol(currency)}`
}

/**
 * The one way an amount is shown (MAG-621), as the mock-ups write it: « 2 743,60 € », « −120,00 € ».
 * Thin no-break space between the thousands, comma, the symbol after the number — never the ISO code.
 */
export function formatCents(cents: number, currency: string | null = 'EUR'): string {
  const value = cents ?? 0

  return money(Math.abs(value) / 100, value < 0, 2, currency)
}

/** « 9 482 € »: whole units, rounded half up — for summary balances, targets and projections, which are not precise to the cent. */
export function formatCentsRounded(cents: number, currency: string | null = 'EUR'): string {
  const value = cents ?? 0
  const units = Math.round(Math.abs(value) / 100)

  return money(units, value < 0 && units !== 0, 0, currency)
}
