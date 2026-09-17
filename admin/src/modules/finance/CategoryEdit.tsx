import { Edit } from 'react-admin'
import { CategoryForm } from './CategoryForm'

export const CategoryEdit = () => (
  <Edit title="Modifier la catégorie">
    <CategoryForm />
  </Edit>
)
