import {
  List,
  Datagrid,
  TextField,
  BooleanField,
  FunctionField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { ACCOUNT_TYPE_LABELS, formatCents } from './accountTypes'
import type { RaRecord } from 'react-admin'

const ACCOUNT_TOPICS = ['/api/accounts/{id}']

const AccountDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(ACCOUNT_TOPICS, () => { refetch() })

  return (
    <Datagrid rowClick="edit">
      <TextField source="name" label="Nom" />
      <TextField source="bank" label="Banque" />
      <FunctionField
        label="Type"
        render={(record: RaRecord) => ACCOUNT_TYPE_LABELS[record.type as string] ?? record.type}
      />
      <FunctionField
        label="Solde"
        render={(record: RaRecord) => formatCents(record.balanceCents as number, record.currency as string)}
      />
      <BooleanField source="isCushion" label="Matelas" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const AccountList = () => (
  <List>
    <AccountDatagrid />
  </List>
)
