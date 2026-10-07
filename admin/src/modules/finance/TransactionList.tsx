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
import { statusLabel } from './transactionStatuses'
import { Amount } from './AmountField'
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
        textAlign="right"
        render={(record: RaRecord) => (
          <Amount
            cents={record.amountCents as number}
            currency={record.currency as string}
            signed
          />
        )}
      />
      <ReferenceField source="account" reference="accounts" label="Compte" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <FunctionField
        label="Statut"
        render={(record: RaRecord) =>
          statusLabel(record.status as string, record.amountCents as number)
        }
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
