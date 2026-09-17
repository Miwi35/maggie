import { Edit } from 'react-admin'
import { TransactionForm } from './TransactionForm'

export const TransactionEdit = () => (
  <Edit title="Modifier l'opération">
    <TransactionForm />
  </Edit>
)
