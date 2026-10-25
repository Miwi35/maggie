import { Edit } from 'react-admin'
import { StoreForm } from './StoreForm'

export const StoreEdit = () => (
  <Edit title="Modifier le magasin">
    <StoreForm />
  </Edit>
)
