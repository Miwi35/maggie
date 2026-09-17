import { Create } from 'react-admin'
import { TransactionForm } from './TransactionForm'

export const TransactionCreate = () => (
  <Create title="Nouvelle opération">
    <TransactionForm withDefaults />
  </Create>
)
