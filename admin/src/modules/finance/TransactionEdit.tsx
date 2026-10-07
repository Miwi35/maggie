import { Edit } from 'react-admin'
import { TransactionForm, toTransactionPayload } from './TransactionForm'

export const TransactionEdit = () => (
  <Edit title="Modifier la transaction" transform={toTransactionPayload}>
    <TransactionForm />
  </Edit>
)
