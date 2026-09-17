import { Create } from 'react-admin'
import { CategoryForm } from './CategoryForm'

export const CategoryCreate = () => (
  <Create title="Nouvelle catégorie">
    <CategoryForm withDefaults />
  </Create>
)
