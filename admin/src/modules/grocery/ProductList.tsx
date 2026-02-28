import {
  List,
  Datagrid,
  TextField,
  NumberField,
  ReferenceField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'

const PRODUCT_TOPICS = ['/api/products/{id}']

const ProductDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(PRODUCT_TOPICS, refetch)

  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <TextField source="category" label="Catégorie" />
      <TextField source="defaultUnit" label="Unité" />
      <NumberField source="shelfLifeDays" label="Conservation (jours)" />
      <ReferenceField source="preferredStore" reference="stores" label="Magasin préféré" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const ProductList = () => (
  <List>
    <ProductDatagrid />
  </List>
)
