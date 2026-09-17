import { Create } from 'react-admin'
import { ProductForm } from './ProductForm'

export const ProductCreate = () => (
  <Create title="Nouveau produit">
    <ProductForm />
  </Create>
)
