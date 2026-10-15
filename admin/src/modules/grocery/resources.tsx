import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { ErrandList } from './ErrandList'
import { GroceryListView } from './GroceryListView'
import { ProductCreate } from './ProductCreate'
import { ProductEdit } from './ProductEdit'
import { ProductList } from './ProductList'
import { StoreCreate } from './StoreCreate'
import { StoreEdit } from './StoreEdit'
import { StoreList } from './StoreList'

export const groceryResources = (
  <>
    <CustomRoutes>
      <Route path="/grocery" element={<GroceryListView />} />
    </CustomRoutes>
    <ResourceGuesser
      name="products"
      list={ProductList}
      create={ProductCreate}
      edit={ProductEdit}
    />
    <ResourceGuesser
      name="stores"
      list={StoreList}
      create={StoreCreate}
      edit={StoreEdit}
    />
    <ResourceGuesser name="errands" list={ErrandList} />
  </>
)
