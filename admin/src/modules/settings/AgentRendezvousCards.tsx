import { useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { UserPreference } from '../../hooks/useUserPreferences'

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
  const [on, setOn] = useState(enabled)
  useEffect(() => setOn(enabled), [enabled])

  const handleEnabled = async (value: boolean) => {
    setOn(value)
    if (!(await onSave({ [enabledKey]: value }))) setOn(enabled)
  }

  const handleTime = async (value: string) => {
    setDraft(value)
    if (value && value !== time && !(await onSave({ [timeKey]: value }))) setDraft(time)
  }

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Stack spacing={1}>
          <FormControlLabel
            control={<Switch checked={on} onChange={(e) => handleEnabled(e.target.checked)} />}
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
            disabled={!on}
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
export const AgentRendezvousCards = ({
  preferences,
  updatePreference,
}: {
  preferences: UserPreference | null
  updatePreference: (patch: RendezvousPatch) => Promise<boolean>
}) => {
  const notify = useNotify()

  if (!preferences) return null

  const save = async (patch: RendezvousPatch) => {
    const ok = await updatePreference(patch)
    if (ok) {
      notify('Enregistré', { type: 'success' })
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
    </>
  )
}
