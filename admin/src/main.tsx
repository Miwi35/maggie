import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'

// Ensure the app is loaded at the correct base path
if (!window.location.pathname.startsWith('/admin')) {
  window.location.replace('/admin')
} else {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}
