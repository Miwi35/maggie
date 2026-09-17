import { Edit } from 'react-admin'
import { AccountForm } from './AccountForm'

export const AccountEdit = () => (
  <Edit title="Modifier le compte">
    <AccountForm />
  </Edit>
)
