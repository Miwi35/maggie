import {
  Datagrid,
  TextField,
  NumberField,
  FunctionField,
  ReferenceField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import { ListEmpty } from '../../components/list/ListEmpty'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { packagingLabel } from './packaging'
import { StockStateChip } from './StockStateChip'

const PRODUCT_TOPICS = ['/api/products/{id}']

const PRODUCT_LIVE: LiveConfig = {
  topics: PRODUCT_TOPICS,
  required: ['name', 'category', 'stockState'],
  references: {
    preferredStoreId: { field: 'preferredStore', collection: '/api/stores' },
    fallbackStoreId: { field: 'fallbackStore', collection: '/api/stores' },
  },
}

const ProductDatagrid = () => {
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
      <FunctionField
        label="Stock"
        render={(record: { stockState?: string | null }) => <StockStateChip state={record.stockState} />}
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
  <LiveList
    live={PRODUCT_LIVE}
    empty={
      <ListEmpty
        title="Aucun produit pour l'instant"
        description="Les produits sont ce que vous ajoutez à votre liste de courses : enregistrez ceux que vous rachetez souvent."
        action="Ajouter un produit"
      />
    }
  >
    <ProductDatagrid />
  </LiveList>
)
