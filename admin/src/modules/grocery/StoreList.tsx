import {
  List,
  Datagrid,
  TextField,
  NumberField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
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
  <List>
    <StoreDatagrid />
  </List>
)
