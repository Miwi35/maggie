import {
  Datagrid,
  TextField,
  DateField,
  BooleanField,
  ReferenceField,
  FunctionField,
  EditButton,
  DeleteButton,
} from 'react-admin'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { statusLabel } from './transactionStatuses'
import { Amount } from './AmountField'
import type { RaRecord } from 'react-admin'

const TRANSACTION_TOPICS = ['/api/transactions/{id}']

const TRANSACTION_LIVE: LiveConfig = {
  topics: TRANSACTION_TOPICS,
  required: ['label', 'amountCents', 'bookedAt', 'account'],
  references: {
    accountId: { field: 'account', collection: '/api/accounts' },
    categoryId: { field: 'category', collection: '/api/categories' },
  },
}

const TransactionDatagrid = () => {
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
  <LiveList live={TRANSACTION_LIVE} sort={{ field: 'bookedAt', order: 'DESC' }}>
    <TransactionDatagrid />
  </LiveList>
)
