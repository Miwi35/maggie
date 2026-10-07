import { useCallback, useEffect, useState } from 'react'
import { useDataProvider, useNotify, useStore } from 'react-admin'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CardHeader from '@mui/material/CardHeader'
import Checkbox from '@mui/material/Checkbox'
import CircularProgress from '@mui/material/CircularProgress'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useUserPreferences } from '../../hooks/useUserPreferences'
import { THEME_CHOICE_KEY } from '../../components/layout/ThemePreferenceSync'
import type { ThemeChoice } from '../../components/layout/ThemePreferenceSync'
import { useMercure } from '../../hooks/useMercure'
import { isSoundEnabled, setSoundEnabled } from '../../components/maggie/chime'

interface Agenda {
  id: string
  name: string
  color?: string
}

const TIMEZONES = Intl.supportedValuesOf('timeZone')

const MERCURE_TOPICS = ['/api/user_preferences/{id}']

export const UserPreferenceSettings = () => {
  const notify = useNotify()
  const dataProvider = useDataProvider()
  const { preferences, updatePreference, loading, refresh } = useUserPreferences()
  const [agendas, setAgendas] = useState<Agenda[]>([])
  const [, setThemeChoice] = useStore<ThemeChoice>(THEME_CHOICE_KEY, 'system')
  const [city, setCity] = useState('')
  const [sound, setSound] = useState(isSoundEnabled)

  const savedCity = preferences?.defaultCity ?? ''
  useEffect(() => {
    setCity(savedCity)
  }, [savedCity])

  useEffect(() => {
    dataProvider
      .getList('agendas', { pagination: { page: 1, perPage: 100 }, sort: { field: 'name', order: 'ASC' }, filter: {} })
      .then(({ data }: { data: Agenda[] }) => setAgendas(data))
      .catch(() => {})
  }, [dataProvider])

  const handleMercure = useCallback(() => {
    refresh()
  }, [refresh])

  useMercure(MERCURE_TOPICS, handleMercure)

  const handleThemeChange = async (theme: ThemeChoice) => {
    if (await updatePreference({ theme })) {
      setThemeChoice(theme)
      notify('Thème mis à jour', { type: 'success' })
    } else {
      notify('Impossible de mettre à jour le thème', { type: 'error' })
    }
  }

  const handleSoundChange = (enabled: boolean) => {
    setSoundEnabled(enabled)
    setSound(enabled)
  }

  const handleTimezoneChange = async (tz: string | null) => {
    if (tz) {
      await updatePreference({ timezone: tz })
      notify('Fuseau horaire mis à jour', { type: 'success' })
    }
  }

  const handleCalendarViewChange = async (view: string) => {
    await updatePreference({ defaultCalendarView: view })
    notify('Vue par défaut mise à jour', { type: 'success' })
  }

  const handleCityBlur = async () => {
    const next = city.trim()
    setCity(next)
    if (next === savedCity) return
    // An empty string is how the API is told to forget the city.
    const saved = await updatePreference({ defaultCity: next })
    if (saved) {
      notify(next ? 'Ville par défaut mise à jour' : 'Ville par défaut retirée', { type: 'success' })
    } else {
      setCity(savedCity)
      notify('Impossible de mettre à jour la ville par défaut', { type: 'error' })
    }
  }

  const handleToggleAgenda = async (agendaId: string) => {
    if (!preferences) return
    const current = preferences.enabledAgendaIds
    const next = current.includes(agendaId) ? current.filter((id) => id !== agendaId) : [...current, agendaId]
    await updatePreference({ enabledAgendaIds: next })
  }

  const handleNotificationsToggle = async (enabled: boolean) => {
    await updatePreference({ notificationsEnabled: enabled })
    notify(enabled ? 'Notifications activées' : 'Notifications désactivées', { type: 'success' })
  }

  if (loading || !preferences) {
    return (
      <Box display="flex" justifyContent="center" mt={4}>
        <CircularProgress />
      </Box>
    )
  }

  return (
    <Box maxWidth={700} mx="auto" mt={2}>
      <Typography variant="h5" mb={3}>
        Préférences
      </Typography>

      {/* Theme */}
      <Card sx={{ mb: 3 }}>
        <CardHeader title="Apparence" />
        <CardContent>
          <TextField
            select
            label="Thème"
            value={preferences.theme}
            onChange={(e) => handleThemeChange(e.target.value as ThemeChoice)}
            size="small"
            fullWidth
          >
            <MenuItem value="system">Système</MenuItem>
            <MenuItem value="light">Clair</MenuItem>
            <MenuItem value="dark">Sombre</MenuItem>
          </TextField>
          <FormControlLabel
            sx={{ mt: 2, display: 'flex' }}
            control={<Switch checked={sound} onChange={(e) => handleSoundChange(e.target.checked)} />}
            label="Son des interruptions de Maggie"
          />
        </CardContent>
      </Card>

      {/* Calendar */}
      <Card sx={{ mb: 3 }}>
        <CardHeader title="Calendrier" />
        <CardContent>
          <Stack spacing={3}>
            <Autocomplete
              options={TIMEZONES}
              value={preferences.timezone}
              onChange={(_e, val) => handleTimezoneChange(val)}
              renderInput={(params) => <TextField {...params} label="Fuseau horaire" size="small" />}
              size="small"
            />

            <Box>
              <Typography variant="subtitle2" gutterBottom>
                Vue par défaut
              </Typography>
              <RadioGroup
                row
                value={preferences.defaultCalendarView}
                onChange={(e) => handleCalendarViewChange(e.target.value)}
              >
                <FormControlLabel value="month" control={<Radio size="small" />} label="Mois" />
                <FormControlLabel value="week" control={<Radio size="small" />} label="Semaine" />
                <FormControlLabel value="day" control={<Radio size="small" />} label="Jour" />
              </RadioGroup>
            </Box>

            {agendas.length > 0 && (
              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  Agendas visibles
                </Typography>
                {agendas.map((agenda) => (
                  <FormControlLabel
                    key={agenda.id}
                    control={
                      <Checkbox
                        checked={preferences.enabledAgendaIds.includes(agenda.id)}
                        onChange={() => handleToggleAgenda(agenda.id)}
                        size="small"
                      />
                    }
                    label={
                      <Box display="flex" alignItems="center" gap={1}>
                        {agenda.color && (
                          <Box
                            sx={{
                              width: 12,
                              height: 12,
                              borderRadius: '50%',
                              backgroundColor: agenda.color,
                              flexShrink: 0,
                            }}
                          />
                        )}
                        <span>{agenda.name}</span>
                      </Box>
                    }
                  />
                ))}
              </Box>
            )}
          </Stack>
        </CardContent>
      </Card>

      {/* Weather */}
      <Card sx={{ mb: 3 }}>
        <CardHeader title="Météo" />
        <CardContent>
          <TextField
            label="Ville par défaut"
            helperText="La météo que Maggie donne quand tu ne précises pas de lieu."
            value={city}
            onChange={(e) => setCity(e.target.value)}
            onBlur={handleCityBlur}
            slotProps={{ htmlInput: { maxLength: 100 } }}
            size="small"
            fullWidth
          />
        </CardContent>
      </Card>

      {/* Notifications */}
      <Card sx={{ mb: 3 }}>
        <CardHeader title="Notifications" />
        <CardContent>
          <FormControlLabel
            control={
              <Switch
                checked={preferences.notificationsEnabled}
                onChange={(e) => handleNotificationsToggle(e.target.checked)}
              />
            }
            label="Activer les notifications"
          />
        </CardContent>
      </Card>
    </Box>
  )
}
