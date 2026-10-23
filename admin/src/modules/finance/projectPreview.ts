import { monthLabel } from './scenariosApi'

/** « mars 2027 », or the fact that the project falls past the 36 months. */
export const purchaseLabel = (month: string | null): string => (month ? monthLabel(month) : 'au-delà de 36 mois')

export const nLabel = (month: string | null): string => (month ? monthLabel(month) : 'non atteinte avant 20 ans')

/** « 3 mois plus tard », « 2 mois plus tôt », « aucun décalage »; null when N is out of reach before or after. */
export const impactLabel = (months: number | null): string => {
  if (months === null) return 'non mesurable : N n’est pas atteinte avant 20 ans dans l’un des deux cas'
  if (months === 0) return 'aucun décalage'
  const count = Math.abs(months)

  return `${count} mois ${months > 0 ? 'plus tard' : 'plus tôt'}`
}
