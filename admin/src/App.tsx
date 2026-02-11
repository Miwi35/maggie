import { HydraAdmin } from '@api-platform/admin'
import { radiantLightTheme, radiantDarkTheme } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import frenchMessages from 'ra-language-french'
import { Layout } from './components/layout/Layout'
import { agendaResources } from './modules/agenda'

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

const i18nProvider = polyglotI18nProvider(() => frenchMessages, 'fr')

function App() {
  return (
    <HydraAdmin
      entrypoint={entrypoint}
      layout={Layout}
      i18nProvider={i18nProvider}
      theme={radiantLightTheme}
      darkTheme={radiantDarkTheme}
    >
      {agendaResources}
    </HydraAdmin>
  )
}

export default App
