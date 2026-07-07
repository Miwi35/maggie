export const TRANSACTION_STATUS_CHOICES = [
  { id: 'spent', name: 'Dépensée' },
  { id: 'committed', name: 'Engagée' },
  { id: 'planned', name: 'Planifiée' },
  { id: 'to_arbitrate', name: 'À arbitrer' },
]

export const TRANSACTION_STATUS_LABELS: Record<string, string> = Object.fromEntries(
  TRANSACTION_STATUS_CHOICES.map((c) => [c.id, c.name]),
)
