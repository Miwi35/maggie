import { Box, Button, Card, CardContent, Typography } from '@mui/material'

const apiUrl = import.meta.env.VITE_API_URL || 'http://localhost/api'

export function LoginPage() {
  return (
    <Box
      display="flex"
      justifyContent="center"
      alignItems="center"
      minHeight="100vh"
    >
      <Card sx={{ minWidth: 350, maxWidth: 400 }}>
        <CardContent
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 3,
            p: 4,
          }}
        >
          <Typography variant="h5" component="h1">
            Maggie
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Connectez-vous pour continuer
          </Typography>
          <Button
            variant="contained"
            href={`${apiUrl}/auth/google/redirect`}
            size="large"
          >
            Se connecter avec Google
          </Button>
        </CardContent>
      </Card>
    </Box>
  )
}
