import { useCallback, useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useMercure } from '../../hooks/useMercure'
import { useUserPreferences } from '../../hooks/useUserPreferences'
import type { UserPreference } from '../../hooks/useUserPreferences'

const MERCURE_TOPICS = ['/api/user_preferences/{id}']

type RendezvousPatch = Partial<
  Pick<UserPreference, 'morningDigestEnabled' | 'morningDigestTime' | 'eveningReviewEnabled' | 'eveningReviewTime'>
>

const RendezvousCard = ({
  title,
  description,
  enabled,
  time,
  onSave,
  enabledKey,
  timeKey,
}: {
  title: string
  description: string
  enabled: boolean
  time: string
  onSave: (patch: RendezvousPatch) => Promise<boolean>
  enabledKey: 'morningDigestEnabled' | 'eveningReviewEnabled'
  timeKey: 'morningDigestTime' | 'eveningReviewTime'
}) => {
  const [draft, setDraft] = useState(time)
  useEffect(() => setDraft(time), [time])

  const handleTime = async (value: string) => {
    setDraft(value)
    if (value && value !== time && !(await onSave({ [timeKey]: value }))) setDraft(time)
  }

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Stack spacing={1}>
          <FormControlLabel
            control={<Switch checked={enabled} onChange={(e) => onSave({ [enabledKey]: e.target.checked })} />}
            label={title}
          />
          <Typography variant="body2" color="text.secondary">
            {description}
          </Typography>
          <TextField
            type="time"
            label={`Heure — ${title}`}
            value={draft}
            onChange={(e) => handleTime(e.target.value)}
            disabled={!enabled}
            size="small"
            sx={{ maxWidth: 160 }}
            slotProps={{ inputLabel: { shrink: true } }}
          />
        </Stack>
      </CardContent>
    </Card>
  )
}

/** Réglages › Maggie (MAG-788): the morning digest and the evening review, each a switch and an hour, saved as they change. */
export const AgentRendezvousCards = () => {
  const notify = useNotify()
  const { preferences, updatePreference, refresh } = useUserPreferences()
  const [saved, setSaved] = useState(false)

  const handleMercure = useCallback(() => {
    refresh()
  }, [refresh])
  useMercure(MERCURE_TOPICS, handleMercure)

  if (!preferences) return null

  const save = async (patch: RendezvousPatch) => {
    setSaved(false)
    const ok = await updatePreference(patch)
    if (ok) {
      setSaved(true)
    } else {
      notify('Impossible d’enregistrer ce rendez-vous', { type: 'error' })
    }
    return ok
  }

  return (
    <>
      <RendezvousCard
        title="Digest du matin"
        description="La journée à venir, par écrit et en vocal."
        enabled={preferences.morningDigestEnabled}
        time={preferences.morningDigestTime}
        enabledKey="morningDigestEnabled"
        timeKey="morningDigestTime"
        onSave={save}
      />
      <RendezvousCard
        title="Bilan du soir"
        description="Le point sur la journée, relancé 30 min plus tard."
        enabled={preferences.eveningReviewEnabled}
        time={preferences.eveningReviewTime}
        enabledKey="eveningReviewEnabled"
        timeKey="eveningReviewTime"
        onSave={save}
      />
      {saved && (
        <Typography variant="body2" color="success.main" sx={{ mb: 2 }}>
          Enregistré
        </Typography>
      )}
    </>
  )
}
