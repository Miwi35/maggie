import {
  Datagrid,
  DateField,
  BooleanField,
  ReferenceField,
  FunctionField,
  DeleteButton,
} from 'react-admin'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { statusLabel } from './transactionStatuses'
import { Amount } from './AmountField'
import type { RaRecord } from 'react-admin'
import { CategoryPathField } from './CategoryPathField'
import { TransactionPills } from './TransactionPills'
import { accountLabel } from './accountLabel'
import type { LabelledAccount } from './accountLabel'

const TRANSACTION_TOPICS = ['/api/transactions/{id}']

const TRANSACTION_LIVE: LiveConfig = {
  topics: TRANSACTION_TOPICS,
  required: ['label', 'amountCents', 'bookedAt', 'account'],
  references: {
    accountId: { field: 'account', collection: '/api/accounts' },
    categoryId: { field: 'category', collection: '/api/categories' },
    recurringOperationId: { field: 'recurringOperation', collection: '/api/recurring_operations' },
  },
}

const TransactionDatagrid = () => {
  return (
    <Datagrid rowClick="edit">
      <DateField source="bookedAt" label="Date" />
      <FunctionField
        label="Libellé"
        render={(record: RaRecord) => (
          <>
            <span>{record.label as string}</span>
            <TransactionPills record={record} />
          </>
        )}
      />
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
        <FunctionField render={(account: RaRecord) => accountLabel(account as LabelledAccount)} />
      </ReferenceField>
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <CategoryPathField />
      </ReferenceField>
      <FunctionField
        label="Statut"
        render={(record: RaRecord) =>
          statusLabel(record.status as string, record.amountCents as number)
        }
      />
      <BooleanField source="isExceptional" label="Exceptionnel" />
      <DeleteButton />
    </Datagrid>
  )
}

export const TransactionList = () => (
  <LiveList live={TRANSACTION_LIVE} sort={{ field: 'bookedAt', order: 'DESC' }}>
    <TransactionDatagrid />
  </LiveList>
)
