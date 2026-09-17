import { Create } from 'react-admin'
import { AccountForm } from './AccountForm'

export const AccountCreate = () => (
  <Create title="Nouveau compte">
    <AccountForm withDefaults />
  </Create>
)
