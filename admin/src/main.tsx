import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { handleAuthCallback } from './auth/authProvider'
import { restoreSession } from './auth/session'
import * as Sentry from '@sentry/react'
import { initSentry } from './observability/sentry'

// First, so an error during the session restore is reported too.
initSentry()

// An expired access token is renewed from the refresh cookie before the router
// reads the location: App sends a visitor with no valid session to the login
// page when it loads, and a tablet that slept past 24 h is not that visitor.
async function start() {
  handleAuthCallback()
  await restoreSession()
  const { default: App } = await import('./App')

  // Render errors that an error boundary catches are reported too.
  createRoot(document.getElementById('root')!, {
    onCaughtError: Sentry.reactErrorHandler(),
    onUncaughtError: Sentry.reactErrorHandler(),
  }).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}

void start()
