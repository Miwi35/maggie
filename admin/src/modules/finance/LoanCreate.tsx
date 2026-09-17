import { Create } from 'react-admin'
import { LoanForm } from './LoanForm'

export const LoanCreate = () => (
  <Create title="Nouveau prêt">
    <LoanForm withDefaults />
  </Create>
)
