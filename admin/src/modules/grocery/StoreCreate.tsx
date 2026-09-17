import { Create } from 'react-admin'
import { StoreForm } from './StoreForm'

export const StoreCreate = () => (
  <Create title="Nouveau magasin">
    <StoreForm />
  </Create>
)
