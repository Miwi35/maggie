import { useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import {
  Datagrid,
  DateField,
  FunctionField,
  ReferenceField,
  DeleteButton,
  CreateButton,
  TopToolbar,
  Title,
  useGetOne,
} from 'react-admin'
import { useParams } from 'react-router-dom'
import { useMercure } from '../../hooks/useMercure'
import { Placeholder } from '../../components/list/ListEmpty'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { statusLabel } from './transactionStatuses'
import { Amount } from './AmountField'
import { TransferCounterpart } from './TransferBadge'
import { TransactionPills } from './TransactionPills'
import { isBetweenAccounts, transactionIdOf } from './useTransactionTransfer'
import { AccountIncidents } from './AccountIncidents'
import { useAccountIncidents } from './useAccountIncidents'
import type { RaRecord } from 'react-admin'
import { CategoryPathField } from './CategoryPathField'

const TRANSACTION_TOPICS = ['/api/transactions/{id}']

const transactionLive = (accountIri: string): LiveConfig => ({
  topics: TRANSACTION_TOPICS,
  required: ['label', 'amountCents', 'bookedAt', 'account'],
  references: {
    accountId: { field: 'account', collection: '/api/accounts' },
    categoryId: { field: 'category', collection: '/api/categories' },
    recurringOperationId: { field: 'recurringOperation', collection: '/api/recurring_operations' },
  },
  accept: (row) => row.account === accountIri,
})

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
            {(isBetweenAccounts(record.transferKind as string | undefined) || record.transferKind === 'rejected') && (
              <TransferCounterpart
                transactionId={transactionIdOf(record.id as string)}
                amountCents={record.amountCents as number}
              />
            )}
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
      <ReferenceField source="category" reference="categories" label="Catégorie" link={false}>
        <CategoryPathField />
      </ReferenceField>
      <FunctionField
        label="Statut"
        render={(record: RaRecord) =>
          statusLabel(record.status as string, record.amountCents as number)
        }
      />
      <DeleteButton />
    </Datagrid>
  )
}

const AddTransactionButton = ({ accountIri }: { accountIri: string }) => (
  <CreateButton
    resource="transactions"
    label="Ajouter une transaction"
    state={{ record: { account: accountIri } }}
  />
)

/**
 * React-admin renders `empty` *instead of* the list, toolbar included: the
 * invitation has to carry its own button or the screen is a dead end (MAG-245).
 */
export const AccountTransactionsEmpty = ({ accountIri }: { accountIri: string }) => (
  <Placeholder
    title="Aucune transaction sur ce compte"
    description="Ajoutez-en une, ou attendez la prochaine synchronisation si ce compte est alimenté automatiquement."
    action={<AddTransactionButton accountIri={accountIri} />}
  />
)

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
  const live = useMemo(() => transactionLive(accountIri), [accountIri])

  const [tab, setTab] = useState(0)
  const { incidents, error, reload } = useAccountIncidents(id)
  // A rejection is detected when its credit lands: the pair leaves the list
  // and enters the incidents on the same message, so both are read again.
  useMercure(TRANSACTION_TOPICS, () => { reload() })

  const actions = (
    <TopToolbar>
      <AddTransactionButton accountIri={accountIri} />
    </TopToolbar>
  )

  return (
    <>
      <Title title={`Transactions — ${accountName}`} />
      <Tabs value={tab} onChange={(_, value: number) => setTab(value)} sx={{ mb: 1 }}>
        <Tab label="Transactions" />
        <Tab label={`Incidents${incidents === null ? '' : ` (${incidents.length})`}`} />
      </Tabs>
      <Box hidden={tab !== 0}>
        <LiveList
          live={live}
          resource="transactions"
          // The view's own <Title> names the account; the list's default one would be
          // portalled next to it and break the app bar's ellipsis (MAG-387).
          title={false}
          filter={{ account: accountIri }}
          empty={<AccountTransactionsEmpty accountIri={accountIri} />}
          actions={actions}
          disableSyncWithLocation
          sort={{ field: 'bookedAt', order: 'DESC' }}
        >
          <TransactionDatagrid />
        </LiveList>
      </Box>
      <Box hidden={tab !== 1}>
        <AccountIncidents incidents={incidents} error={error} />
      </Box>
    </>
  )
}
