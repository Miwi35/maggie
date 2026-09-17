import { Edit } from 'react-admin'
import { clearMonthOnAnnual } from './budgetModes'
import { EnvelopeForm } from './EnvelopeForm'

export const EnvelopeEdit = () => (
  <Edit transform={clearMonthOnAnnual} title="Modifier l'enveloppe">
    <EnvelopeForm />
  </Edit>
)
