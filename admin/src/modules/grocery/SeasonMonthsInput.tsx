import { CheckboxGroupInput } from 'react-admin'

export const SEASON_MONTH_CHOICES = [
  { id: 1, name: 'Janv.' },
  { id: 2, name: 'Févr.' },
  { id: 3, name: 'Mars' },
  { id: 4, name: 'Avr.' },
  { id: 5, name: 'Mai' },
  { id: 6, name: 'Juin' },
  { id: 7, name: 'Juil.' },
  { id: 8, name: 'Août' },
  { id: 9, name: 'Sept.' },
  { id: 10, name: 'Oct.' },
  { id: 11, name: 'Nov.' },
  { id: 12, name: 'Déc.' },
]

// The API keeps the months sorted; a form sends them in the order they were ticked.
const sorted = (months?: number[] | null) => (months == null ? months : [...months].sort((a, b) => a - b))

/**
 * The months a product is in season (MAG-494), one box per month. Pre-filled by
 * the API from the seasonality calendar when the product is created; none
 * ticked is a product without a season.
 */
export const SeasonMonthsInput = () => (
  <CheckboxGroupInput
    source="seasonMonths"
    label="Mois de saison"
    choices={SEASON_MONTH_CHOICES}
    parse={sorted}
    helperText="Pré-rempli pour les fruits et légumes connus ; aucun mois coché : pas de saison."
  />
)
