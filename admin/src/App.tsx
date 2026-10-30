import { HydraAdmin, fetchHydra, hydraDataProvider } from '@api-platform/admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { messages } from './i18n/messages'
import { veilleuseLightTheme, veilleuseDarkTheme } from './theme'
import { Layout } from './components/layout/Layout'
import { calendarResources } from './modules/calendar'
import { settingsResources } from './modules/settings'
import { groceryResources } from './modules/grocery'
import { cookbookResources } from './modules/cookbook'
import { financeResources } from './modules/finance'
import { healthResources } from './modules/health'
import { searchResources } from './modules/search'
import {
  authProvider,
  routeVisitorWithoutSessionToLogin,
} from './auth/authProvider'
import { LoginPage } from './auth/LoginPage'
import { LoadingPage } from './auth/LoadingPage'
import { getToken, installAuthRefresh, startSessionKeeper } from './auth/session'
import { fetchApiDocumentation } from './apiDocumentation'
import { lazyPage } from './lazyPage'
import type { HttpClientOptions } from '@api-platform/admin'

// Out of the first chunk: the landing screen pulls the cards and their widgets.
const Home = lazyPage(() => import('./modules/home/Home'), 'Home')

routeVisitorWithoutSessionToLogin()
installAuthRefresh()
startSessionKeeper()

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const httpClient = (url: URL, options: HttpClientOptions = {}) => {
  const token = getToken()
  if (token) {
    options.user = { authenticated: true, token: `Bearer ${token}` }
  }
  return fetchHydra(url, options)
}

const dataProvider = hydraDataProvider({
  entrypoint,
  httpClient,
  apiDocumentationParser: (entrypointUrl: string) => fetchApiDocumentation(entrypointUrl),
})

const HomePage = () => <Home />

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
      dashboard={HomePage}
      i18nProvider={i18nProvider}
      theme={veilleuseLightTheme}
      darkTheme={veilleuseDarkTheme}
    >
      {calendarResources}
      {groceryResources}
      {cookbookResources}
      {healthResources}
      {financeResources}
      {settingsResources}
      {searchResources}
    </HydraAdmin>
  )
}

export default App
