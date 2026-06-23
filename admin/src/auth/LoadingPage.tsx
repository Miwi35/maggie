import { useEffect, useState } from 'react'
import { Box, CircularProgress } from '@mui/material'

// Delay before the splash is shown, in ms. React-admin re-mounts this component
// for every transient loading state (auth check, dashboard permissions, resource
// config). On a cold load those states last long enough to flash the splash on and
// off repeatedly. By rendering nothing until the timer fires — and since the
// component unmounts before the timer completes during a flicker — brief loading
// states never reveal the splash. Mirrors react-admin's built-in <Loading delay>.
const SPLASH_DELAY_MS = 500

export function LoadingPage() {
  const [show, setShow] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setShow(true), SPLASH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [])

  if (!show) {
    return null
  }

  return (
    <Box
      display="flex"
      flexDirection="column"
      justifyContent="center"
      alignItems="center"
      minHeight="100vh"
      gap={2}
      sx={{ bgcolor: '#1a1a2e' }}
    >
      <Box
        component="img"
        src="/admin/maggie.png"
        alt="Maggie"
        sx={{ width: 160, height: 'auto' }}
      />
      <Box
        component="img"
        src="/admin/logo.svg"
        alt="Maggie"
        sx={{ width: 180, height: 'auto' }}
      />
      <CircularProgress size={28} sx={{ color: 'rgba(255,255,255,0.7)', mt: 3 }} />
    </Box>
  )
}
