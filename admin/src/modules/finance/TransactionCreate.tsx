import { Create } from 'react-admin'
import { TransactionForm, toTransactionPayload } from './TransactionForm'

export const TransactionCreate = () => (
  <Create title="Nouvelle transaction" transform={toTransactionPayload}>
    <TransactionForm withDefaults />
  </Create>
)
