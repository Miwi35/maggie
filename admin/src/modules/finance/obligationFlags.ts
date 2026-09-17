export const OBLIGATION_CHOICES = [
  { id: 'mandatory', name: 'Obligatoire' },
  { id: 'optional', name: 'Non-obligatoire' },
  { id: 'saving', name: 'Épargne' },
  { id: 'investment', name: 'Investissement' },
  { id: 'income', name: 'Recette' },
]

export const OBLIGATION_LABELS: Record<string, string> = Object.fromEntries(
  OBLIGATION_CHOICES.map((c) => [c.id, c.name]),
)
