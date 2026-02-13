import { Box, Button, Card, CardContent, Typography } from '@mui/material'

const apiUrl = import.meta.env.VITE_API_URL || 'http://localhost/api'

export function LoginPage() {
  return (
    <Box
      display="flex"
      justifyContent="center"
      alignItems="center"
      minHeight="100vh"
      sx={{ bgcolor: '#1a1a2e' }}
    >
      <Card
        sx={{
          minWidth: 360,
          maxWidth: 420,
          borderRadius: 3,
          boxShadow: 6,
          bgcolor: '#16213e',
        }}
      >
        <CardContent
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 2,
            px: 5,
            py: 4,
          }}
        >
          <Box
            component="img"
            src="/admin/maggie.png"
            alt="Maggie"
            sx={{ width: 140, height: 'auto' }}
          />
          <Box
            component="img"
            src="/admin/logo.svg"
            alt="Maggie"
            sx={{ width: 160, height: 'auto' }}
          />
          <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.6)', mt: 1 }}>
            Connectez-vous pour continuer
          </Typography>
          <Button
            href={`${apiUrl}/auth/google/redirect`}
            variant="contained"
            size="large"
            sx={{
              mt: 1,
              bgcolor: '#fff',
              color: 'rgba(0,0,0,0.54)',
              fontWeight: 500,
              fontSize: '0.875rem',
              textTransform: 'none',
              borderRadius: '4px',
              px: 3,
              py: 1,
              boxShadow: '0 1px 3px rgba(0,0,0,0.25)',
              '&:hover': { bgcolor: '#f5f5f5' },
            }}
            startIcon={
              <Box
                component="img"
                src="/admin/google-g.svg"
                alt=""
                sx={{ width: 20, height: 20 }}
              />
            }
          >
            Se connecter avec Google
          </Button>
        </CardContent>
      </Card>
    </Box>
  )
}
