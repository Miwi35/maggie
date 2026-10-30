import Box from '@mui/material/Box'
import LinearProgress from '@mui/material/LinearProgress'
import Typography from '@mui/material/Typography'
import { ActivityCard, NeedCard, ProfileCard } from './ConfigurationCards'
import { useHealthProfile } from './healthProfileApi'

/**
 * Santé › Configuration (MAG-707): the profile and its day's need, then the
 * activity settings, on the mockup's grid. The heading is the layout's. Every
 * change is saved at once, one field per PATCH.
 */
export const ConfigurationPage = () => {
  const { profile, loadFailed, save } = useHealthProfile()

  if (!profile) {
    return loadFailed ? (
      <Typography sx={{ py: 2, color: 'text.secondary' }}>Le profil n’a pas pu être chargé.</Typography>
    ) : (
      <LinearProgress sx={{ my: 2 }} />
    )
  }

  return (
    <Box data-testid="health-configuration" sx={{ display: 'flex', flexDirection: 'column', gap: 2.25, py: 1, fontVariantNumeric: 'tabular-nums' }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '420px minmax(0, 1fr)' }, gap: 2, alignItems: 'start' }}>
        <ProfileCard profile={profile} save={save} />
        <NeedCard profile={profile} />
      </Box>
      <ActivityCard profile={profile} save={save} />
    </Box>
  )
}
