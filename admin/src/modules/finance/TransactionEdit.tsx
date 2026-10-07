import { Edit } from 'react-admin'
import { TransactionForm, toTransactionPayload } from './TransactionForm'
import { TransferPanel } from './TransferPanel'

export const TransactionEdit = () => (
  <Edit title="Modifier la transaction" transform={toTransactionPayload}>
    <TransferPanel />
    <TransactionForm />
  </Edit>
)
