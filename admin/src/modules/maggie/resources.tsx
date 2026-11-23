import { CustomRoutes } from 'react-admin'
import { Route } from 'react-router-dom'
import { lazyPage } from '../../lazyPage'
import { BILAN_ACTIVITY_PATH } from './bilan/bilanStatusApi'

const DigestPage = lazyPage(() => import('./digest/DigestPage'), 'DigestPage')
const BilanActivityPage = lazyPage(() => import('./bilan/BilanActivityPage'), 'BilanActivityPage')

export const maggieResources = (
  <>
    <CustomRoutes>
      <Route path="/digest" element={<DigestPage />} />
    </CustomRoutes>
    {/* The evening review (MAG-961): full screen, out of the menus. */}
    <CustomRoutes noLayout>
      <Route path={BILAN_ACTIVITY_PATH} element={<BilanActivityPage />} />
    </CustomRoutes>
  </>
)
