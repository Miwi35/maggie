export const ACCOUNT_TYPE_CHOICES = [
  { id: 'checking', name: 'Compte courant' },
  { id: 'savings', name: 'Épargne' },
  { id: 'investment', name: 'Investissement' },
  { id: 'cash', name: 'Espèces' },
]

export const ACCOUNT_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  ACCOUNT_TYPE_CHOICES.map((c) => [c.id, c.name]),
)

/** Format an integer amount in cents to a localized currency string. */
export function formatCents(cents: number, currency = 'EUR'): string {
  const amount = (cents ?? 0) / 100
  try {
    return new Intl.NumberFormat('fr-FR', { style: 'currency', currency }).format(amount)
  } catch {
    return `${amount.toFixed(2)} ${currency}`
  }
}
