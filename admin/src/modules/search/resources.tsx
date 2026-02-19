import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { SearchPage } from './SearchPage'

export const searchResources = (
  <CustomRoutes>
    <Route path="/search" element={<SearchPage />} />
  </CustomRoutes>
)
