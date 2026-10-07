import {
  List,
  Datagrid,
  TextField,
  NumberField,
  FunctionField,
  ReferenceField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { ListEmpty } from '../../components/list/ListEmpty'
import { useMercure } from '../../hooks/useMercure'
import { packagingLabel } from './packaging'

const PRODUCT_TOPICS = ['/api/products/{id}']

const ProductDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(PRODUCT_TOPICS, () => { refetch() })

  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <TextField source="category" label="Catégorie" />
      <TextField source="defaultUnit" label="Unité" />
      <FunctionField
        label="Conditionnement"
        render={(record: { packagingUnit?: string | null; packagingSize?: number | null; packagingSizeUnit?: string | null }) =>
          packagingLabel(record.packagingUnit, record.packagingSize, record.packagingSizeUnit) ?? ''
        }
      />
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
  <List
    empty={
      <ListEmpty
        title="Aucun produit pour l'instant"
        description="Les produits sont ce que vous ajoutez à votre liste de courses : enregistrez ceux que vous rachetez souvent."
        action="Ajouter un produit"
      />
    }
  >
    <ProductDatagrid />
  </List>
)
