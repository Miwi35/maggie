import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'

const ErrandList = lazyPage(() => import('./ErrandList'), 'ErrandList')
const GroceryListView = lazyPage(() => import('./GroceryListView'), 'GroceryListView')
const ProductCreate = lazyPage(() => import('./ProductCreate'), 'ProductCreate')
const ProductEdit = lazyPage(() => import('./ProductEdit'), 'ProductEdit')
const ProductList = lazyPage(() => import('./ProductList'), 'ProductList')
const StoreCreate = lazyPage(() => import('./StoreCreate'), 'StoreCreate')
const StoreEdit = lazyPage(() => import('./StoreEdit'), 'StoreEdit')
const StoreList = lazyPage(() => import('./StoreList'), 'StoreList')

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
