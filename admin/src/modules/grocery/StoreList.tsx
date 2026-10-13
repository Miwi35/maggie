import {
  Datagrid,
  TextField,
  NumberField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import { ListEmpty } from '../../components/list/ListEmpty'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'

const STORE_TOPICS = ['/api/stores/{id}']

const STORE_LIVE: LiveConfig = {
  topics: STORE_TOPICS,
  required: ['name', 'visitOrder'],
}

const StoreDatagrid = () => {
  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <TextField source="description" label="Description" />
      <NumberField source="visitOrder" label="Ordre de visite" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const StoreList = () => (
  <LiveList
    live={STORE_LIVE}
    empty={
      <ListEmpty
        title="Aucun magasin pour l'instant"
        description="Les magasins découpent la liste de courses et s'affichent dans l'ordre de votre tournée."
        action="Ajouter un magasin"
      />
    }
  >
    <StoreDatagrid />
  </LiveList>
)
