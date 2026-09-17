import { Edit } from 'react-admin'
import { LoanForm } from './LoanForm'

export const LoanEdit = () => (
  <Edit title="Modifier le prêt">
    <LoanForm />
  </Edit>
)
