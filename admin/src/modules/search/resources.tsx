import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'

const SearchPage = lazyPage(() => import('./SearchPage'), 'SearchPage')

export const searchResources = (
  <CustomRoutes>
    <Route path="/search" element={<SearchPage />} />
  </CustomRoutes>
)
