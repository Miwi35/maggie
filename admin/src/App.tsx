import { HydraAdmin, fetchHydra, hydraDataProvider } from '@api-platform/admin'
import { parseHydraDocumentation } from '@api-platform/api-doc-parser'
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

const getAuthHeaders = (): HeadersInit => {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

const httpClient = (url: URL, options: HttpClientOptions = {}) => {
  const token = localStorage.getItem('token')
  if (token) {
    options.user = { authenticated: true, token: `Bearer ${token}` }
  }
  return fetchHydra(url, options)
}

const apiDocumentationParser = async (entrypointUrl: string) => {
  try {
    return await parseHydraDocumentation(entrypointUrl, { headers: getAuthHeaders })
  } catch (error) {
    const status = (error as { status?: number }).status
    if (status === 401 || status === 403) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.reload()
      throw error
    }
    throw error
  }
}

const dataProvider = hydraDataProvider({
  entrypoint,
  httpClient,
  apiDocumentationParser,
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
