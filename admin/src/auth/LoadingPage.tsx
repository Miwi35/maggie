import { Box, CircularProgress } from '@mui/material'

export function LoadingPage() {
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
