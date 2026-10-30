import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Button from '@mui/material/Button'
import { useProviderStatus, type ProviderStatus } from '../../hooks/useProviderStatus'

const BANNERS: Record<Exclude<ProviderStatus, 'ok'>, { subtitle: string; action: string; url: string }> = {
  credit: {
    subtitle: 'Crédit Anthropic épuisé',
    action: 'Recharger',
    url: 'https://console.anthropic.com/settings/billing',
  },
  auth: {
    subtitle: 'Clé Anthropic refusée',
    action: 'Ouvrir la console',
    url: 'https://console.anthropic.com/settings/keys',
  },
}

/** « Maggie ne peut plus répondre » (MAG-727): not dismissable, it goes when the state is back to `ok`. */
export function ProviderBanner() {
  const status = useProviderStatus()
  if (status === 'ok') return null
  const banner = BANNERS[status]

  return (
    <Alert
      data-testid="provider-banner"
      severity="error"
      sx={{ borderRadius: 0 }}
      action={
        <Button color="inherit" size="small" href={banner.url} target="_blank" rel="noopener noreferrer">
          {banner.action}
        </Button>
      }
    >
      <AlertTitle>Maggie ne peut plus répondre</AlertTitle>
      {banner.subtitle}
    </Alert>
  )
}
