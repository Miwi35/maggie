import { HydraAdmin, fetchHydra, hydraDataProvider } from '@api-platform/admin'
import { radiantLightTheme, radiantDarkTheme } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import frenchMessages from 'ra-language-french'
import { Layout } from './components/layout/Layout'
import { Dashboard } from './modules/dashboard'
import { calendarResources } from './modules/calendar'
import { settingsResources } from './modules/settings'
import { cookbookResources } from './modules/cookbook'
import { searchResources } from './modules/search'
import { authProvider, handleAuthCallback } from './auth/authProvider'
import { LoginPage } from './auth/LoginPage'
import { LoadingPage } from './auth/LoadingPage'
import type { HttpClientOptions } from '@api-platform/admin'

// Handle OAuth callback params before React renders
handleAuthCallback()

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

const i18nProvider = polyglotI18nProvider(() => frenchMessages, 'fr')

const httpClient = (url: URL, options: HttpClientOptions = {}) => {
  const token = localStorage.getItem('token')
  if (token) {
    options.user = { authenticated: true, token: `Bearer ${token}` }
  }
  return fetchHydra(url, options)
}

const dataProvider = hydraDataProvider({
  entrypoint,
  httpClient,
})

function App() {
  return (
    <HydraAdmin
      entrypoint={entrypoint}
      dataProvider={dataProvider}
      authProvider={authProvider}
      requireAuth
      loginPage={<LoginPage />}
      loading={LoadingPage}
      layout={Layout}
      dashboard={Dashboard}
      i18nProvider={i18nProvider}
      theme={radiantLightTheme}
      darkTheme={radiantDarkTheme}
    >
      {calendarResources}
      {cookbookResources}
      {settingsResources}
      {searchResources}
    </HydraAdmin>
  )
}

export default App
