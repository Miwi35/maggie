/** Basis points are how the API stores rates: 350 is 3,50 %. */
export const basisPointsInput = {
  format: (v?: number) => (v == null ? null : v / 100),
  parse: (v?: number) => (v == null || Number.isNaN(v) ? null : Math.round(v * 100)),
}

export const centsInput = {
  format: (v?: number) => (v == null ? null : v / 100),
  parse: (v?: number) => (v == null || Number.isNaN(v) ? null : Math.round(v * 100)),
}

/** "3,50 %" from 350 basis points. */
export function formatRate(basisPoints = 0): string {
  return `${(basisPoints / 100).toFixed(2).replace('.', ',')} %`
}

/** "Mars 2027" from the API's "2027-03". */
const MONTHS = [
  'Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin',
  'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre',
]

export function formatMonth(month?: string | null): string {
  if (!month) {
    return '—'
  }
  const [year, monthNumber] = month.split('-')
  const name = MONTHS[Number(monthNumber) - 1]

  return name ? `${name} ${year}` : month
}

/** How long is left, said the way a person would. */
export function formatRemaining(monthsRemaining?: number | null): string {
  if (monthsRemaining == null) {
    return "Au-delà de l'horizon"
  }
  if (monthsRemaining < 12) {
    return `${monthsRemaining} mois`
  }

  const years = Math.floor(monthsRemaining / 12)
  const months = monthsRemaining % 12

  return months === 0 ? `${years} an${years > 1 ? 's' : ''}` : `${years} an${years > 1 ? 's' : ''} et ${months} mois`
}
