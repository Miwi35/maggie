import { Edit } from 'react-admin'
import { StoreForm } from './StoreForm'
import { StoreAisles } from './StoreAisles'

export const StoreEdit = () => (
  <Edit title="Modifier le magasin">
    <StoreForm />
    <StoreAisles />
  </Edit>
)
