import {
  List,
  Datagrid,
  TextField,
  NumberField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { ListEmpty } from '../../components/list/ListEmpty'
import { useMercure } from '../../hooks/useMercure'

const STORE_TOPICS = ['/api/stores/{id}']

const StoreDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(STORE_TOPICS, () => { refetch() })

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
  <List
    empty={
      <ListEmpty
        title="Aucun magasin pour l'instant"
        description="Les magasins découpent la liste de courses et s'affichent dans l'ordre de votre tournée."
        action="Ajouter un magasin"
      />
    }
  >
    <StoreDatagrid />
  </List>
)
