import {
  List,
  Datagrid,
  TextField,
  DateField,
  BooleanField,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
  useListContext,
} from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { TRANSACTION_STATUS_LABELS } from './transactionStatuses'
import { formatCents } from './accountTypes'
import type { RaRecord } from 'react-admin'

const TRANSACTION_TOPICS = ['/api/transactions/{id}']

const TransactionDatagrid = () => {
  const { refetch } = useListContext()
  useMercure(TRANSACTION_TOPICS, () => { refetch() })

  return (
    <Datagrid rowClick="edit">
      <DateField source="bookedAt" label="Date" />
      <TextField source="label" label="Libellé" />
      <FunctionField
        label="Montant"
        render={(record: RaRecord) => formatCents(record.amountCents as number, record.currency as string)}
      />
      <ReferenceField source="account" reference="accounts" label="Compte" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <FunctionField
        label="Statut"
        render={(record: RaRecord) => TRANSACTION_STATUS_LABELS[record.status as string] ?? record.status}
      />
      <BooleanField source="isExceptional" label="Exceptionnel" />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

export const TransactionList = () => (
  <List sort={{ field: 'bookedAt', order: 'DESC' }}>
    <TransactionDatagrid />
  </List>
)
