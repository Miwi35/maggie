import {
  List,
  Datagrid,
  TextField,
  DateField,
  FunctionField,
  ReferenceField,
  EditButton,
  DeleteButton,
  CreateButton,
  TopToolbar,
  Title,
  useGetOne,
  useListContext,
} from 'react-admin'
import { useParams } from 'react-router-dom'
import { useMercure } from '../../hooks/useMercure'
import { Placeholder } from '../../components/list/ListEmpty'
import { TRANSACTION_STATUS_LABELS } from './transactionStatuses'
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
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <TextField source="name" />
      </ReferenceField>
      <FunctionField
        label="Statut"
        render={(record: RaRecord) => TRANSACTION_STATUS_LABELS[record.status as string] ?? record.status}
      />
      <EditButton />
      <DeleteButton />
    </Datagrid>
  )
}

/**
 * Account-scoped transaction list — transactions are accessed primarily through
 * their account (banking-app pattern), reached by clicking an account row.
 */
export const AccountTransactionsView = () => {
  const { id } = useParams()
  // The list below filters on `account` with this IRI, not on `accountId`.
  // API Platform names a relation filter after the property, and `accountId`
  // was declared nowhere: it narrowed the list only because the Elasticsearch
  // translator turned any unrecognised string into a term query, and not at
  // all once the collection fell back to Doctrine.
  const accountIri = `/api/accounts/${id}`
  const { data: account } = useGetOne('accounts', { id: accountIri })
  const accountName = (account?.name as string) ?? 'Compte'

  const actions = (
    <TopToolbar>
      <CreateButton
        resource="transactions"
        label="Ajouter une opération"
        state={{ record: { account: accountIri } }}
      />
    </TopToolbar>
  )

  return (
    <>
      <Title title={`Opérations — ${accountName}`} />
      <List
        resource="transactions"
        filter={{ account: accountIri }}
        empty={
          <Placeholder
            title="Aucune opération sur ce compte"
            description="Ajoutez-en une, ou attendez la prochaine synchronisation si ce compte est alimenté automatiquement."
          />
        }
        actions={actions}
        disableSyncWithLocation
        sort={{ field: 'bookedAt', order: 'DESC' }}
      >
        <TransactionDatagrid />
      </List>
    </>
  )
}
