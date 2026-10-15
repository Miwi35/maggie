import { Create } from 'react-admin'
import { toEnvelopePayload } from './budgetModes'
import { EnvelopeForm } from './EnvelopeForm'

export const EnvelopeCreate = () => (
  <Create transform={toEnvelopePayload} title="Nouvelle enveloppe">
    <EnvelopeForm withDefaults />
  </Create>
)
