import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'

const DigestPage = lazyPage(() => import('./digest/DigestPage'), 'DigestPage')

export const maggieResources = (
  <CustomRoutes>
    <Route path="/digest" element={<DigestPage />} />
  </CustomRoutes>
)
