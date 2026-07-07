import {
  List,
  Datagrid,
  TextField,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { OBLIGATION_LABELS } from './obligationFlags'
import type { RaRecord } from 'react-admin'

const CATEGORY_TOPICS = ['/api/categories/{id}']

const CategoryDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(CATEGORY_TOPICS, () => { refetch() })

  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <FunctionField
        label="Obligation"
        render={(record: RaRecord) => OBLIGATION_LABELS[record.obligation as string] ?? record.obligation}
      />
      <ReferenceField source="parent" reference="categories" label="Catégorie parente" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <TextField source="color" label="Couleur" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const CategoryList = () => (
  <List>
    <CategoryDatagrid />
  </List>
)
