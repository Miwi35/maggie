import { ResourceGuesser } from '@api-platform/admin'
import { AccountCreate } from './AccountCreate'
import { AccountEdit } from './AccountEdit'
import { AccountList } from './AccountList'
import { CategoryCreate } from './CategoryCreate'
import { CategoryEdit } from './CategoryEdit'
import { CategoryList } from './CategoryList'
import { TransactionCreate } from './TransactionCreate'
import { TransactionEdit } from './TransactionEdit'
import { TransactionList } from './TransactionList'

export const financeResources = (
  <>
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
    <ResourceGuesser
      name="transactions"
      list={TransactionList}
      create={TransactionCreate}
      edit={TransactionEdit}
    />
  </>
)
