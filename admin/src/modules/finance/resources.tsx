import { ResourceGuesser } from '@api-platform/admin'
import { AccountCreate } from './AccountCreate'
import { AccountEdit } from './AccountEdit'
import { AccountList } from './AccountList'

export const financeResources = (
  <ResourceGuesser
    name="accounts"
    list={AccountList}
    create={AccountCreate}
    edit={AccountEdit}
  />
)
