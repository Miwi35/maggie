import { HydraAdmin, ResourceGuesser } from '@api-platform/admin'
import { Layout } from './components/layout/Layout'

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

function App() {
  return (
    <HydraAdmin entrypoint={entrypoint} layout={Layout}>
      <ResourceGuesser name="calendars" />
      <ResourceGuesser name="events" />
    </HydraAdmin>
  )
}

export default App
