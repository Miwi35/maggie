import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { GroceryListView } from './GroceryListView'
import { ProductList } from './ProductList'
import { ProductCreate } from './ProductCreate'
import { ProductEdit } from './ProductEdit'
import { StoreList } from './StoreList'
import { StoreCreate } from './StoreCreate'
import { StoreEdit } from './StoreEdit'

export const groceryResources = (
  <>
    <CustomRoutes>
      <Route path="/grocery" element={<GroceryListView />} />
    </CustomRoutes>
    <ResourceGuesser name="products" list={ProductList} create={ProductCreate} edit={ProductEdit} />
    <ResourceGuesser name="recurring_grocery_items" />
    <ResourceGuesser name="stores" list={StoreList} create={StoreCreate} edit={StoreEdit} />
  </>
)
