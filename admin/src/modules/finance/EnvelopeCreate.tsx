import { Create } from 'react-admin'
import { clearMonthOnAnnual } from './budgetModes'
import { EnvelopeForm } from './EnvelopeForm'

export const EnvelopeCreate = () => (
  <Create transform={clearMonthOnAnnual} title="Nouvelle enveloppe">
    <EnvelopeForm withDefaults />
  </Create>
)
