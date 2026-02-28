import { ResourceGuesser } from '@api-platform/admin'
import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { GroceryListView } from './GroceryListView'

export const groceryResources = (
  <>
    <CustomRoutes>
      <Route path="/grocery" element={<GroceryListView />} />
    </CustomRoutes>
    <ResourceGuesser name="products" />
    <ResourceGuesser name="recurring_grocery_items" />
    <ResourceGuesser name="stores" />
  </>
)
