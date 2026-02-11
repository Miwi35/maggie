import { HydraAdmin } from '@api-platform/admin'
import { Layout } from './components/layout/Layout'
import { agendaResources } from './modules/agenda'

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

function App() {
  return (
    <HydraAdmin entrypoint={entrypoint} layout={Layout}>
      {agendaResources}
    </HydraAdmin>
  )
}

export default App
