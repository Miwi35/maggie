import { useCallback, useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CardHeader from '@mui/material/CardHeader'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useMercure } from '../../hooks/useMercure'
import { GuestsStepper } from '../cookbook/GuestsStepper'

/**
 * Réglages › Repas (MAG-483): how many people a meal is cooked for when it
 * does not say. The phone reads the same setting: `GET /api/meal-settings/me`.
 */

const API_PATH = '/api/meal-settings/me'
const MERCURE_TOPICS = ['/api/meal_settings/{id}']

interface MealSettingsResponse {
  id: string
  defaultGuests: number
  defaults: { defaultGuests: number }
}

function apiFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')
  return fetch(path, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}

export const MealSettings = () => {
  const notify = useNotify()
  const [settings, setSettings] = useState<MealSettingsResponse | null>(null)
  const [guests, setGuests] = useState(1)
  const [loadFailed, setLoadFailed] = useState(false)
  const [saving, setSaving] = useState(false)

  const apply = useCallback((data: MealSettingsResponse) => {
    setSettings(data)
    setGuests(data.defaultGuests)
  }, [])

  const load = useCallback(async () => {
    try {
      const res = await apiFetch(API_PATH)
      if (!res.ok) throw new Error(String(res.status))
      apply(await res.json())
      setLoadFailed(false)
    } catch {
      setLoadFailed(true)
    }
  }, [apply])

  useEffect(() => {
    load()
  }, [load])

  useMercure(MERCURE_TOPICS, load)

  if (loadFailed && !settings) {
    return (
      <Box maxWidth={700} mx="auto" mt={2}>
        <Alert severity="error">Impossible de charger les réglages des repas</Alert>
      </Box>
    )
  }

  if (!settings) {
    return (
      <Box display="flex" justifyContent="center" mt={4}>
        <CircularProgress />
      </Box>
    )
  }

  const handleSave = async () => {
    setSaving(true)
    try {
      const res = await apiFetch(API_PATH, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ defaultGuests: guests }),
      })
      if (res.ok) {
        apply(await res.json())
        notify('Réglages des repas enregistrés', { type: 'success' })
      } else {
        const body = await res.json().catch(() => null)
        const message =
          res.status === 400 && typeof body?.error === 'string' ? body.error : "Impossible d'enregistrer les réglages des repas"
        notify(message, { type: 'error' })
      }
    } catch {
      notify("Impossible d'enregistrer les réglages des repas", { type: 'error' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box maxWidth={700} mx="auto" mt={2}>
      <Typography variant="h5" mb={3}>
        Repas
      </Typography>

      <Card sx={{ mb: 3 }}>
        <CardHeader title="Convives" />
        <CardContent>
          <Stack spacing={1}>
            <GuestsStepper label="Nombre de convives par défaut" value={guests} onChange={setGuests} disabled={saving} />
            <Typography variant="body2" color="text.secondary">
              Un nouveau repas est prévu pour ce nombre de personnes, et les besoins de la liste de courses s'y adaptent.
            </Typography>
          </Stack>
        </CardContent>
      </Card>

      <Stack direction="row" spacing={2} justifyContent="flex-end" mb={4}>
        <Button variant="contained" onClick={handleSave} disabled={saving}>
          Enregistrer
        </Button>
      </Stack>
    </Box>
  )
}
