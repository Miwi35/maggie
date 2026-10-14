import { Edit } from 'react-admin'
import { toEnvelopePayload } from './budgetModes'
import { EnvelopeForm } from './EnvelopeForm'

export const EnvelopeEdit = () => (
  <Edit transform={toEnvelopePayload} title="Modifier l'enveloppe">
    <EnvelopeForm />
  </Edit>
)
