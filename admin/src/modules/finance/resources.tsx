import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { AccountCreate } from './AccountCreate'
import { AccountEdit } from './AccountEdit'
import { AccountList } from './AccountList'
import { AccountTransactionsView } from './AccountTransactionsView'
import { CategoryCreate } from './CategoryCreate'
import { CategoryEdit } from './CategoryEdit'
import { CategoryList } from './CategoryList'
import { TransactionCreate } from './TransactionCreate'
import { TransactionEdit } from './TransactionEdit'
import { TransactionList } from './TransactionList'

export const financeResources = (
  <>
    <CustomRoutes>
      <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
    </CustomRoutes>
    <ResourceGuesser
      name="accounts"
      list={AccountList}
      create={AccountCreate}
      edit={AccountEdit}
    />
    <ResourceGuesser
      name="categories"
      list={CategoryList}
      create={CategoryCreate}
      edit={CategoryEdit}
    />
    {/* Transactions are reached through their account (see AccountTransactionsView),
        not from the menu; the resource stays fully registered for routing. */}
    <ResourceGuesser
      name="transactions"
      list={TransactionList}
      create={TransactionCreate}
      edit={TransactionEdit}
    />
  </>
)
