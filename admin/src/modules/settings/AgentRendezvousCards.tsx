import { useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import type { UserPreference } from '../../hooks/useUserPreferences'

type RendezvousPatch = Partial<
  Pick<
    UserPreference,
    | 'morningDigestEnabled'
    | 'morningDigestTime'
    | 'eveningReviewEnabled'
    | 'eveningReviewTime'
    | 'workStartTime'
    | 'workEndTime'
    | 'workDays'
  >
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

// ISO day, letter on the pill, name read out.
const WORK_DAYS: [number, string, string][] = [
  [1, 'L', 'lundi'],
  [2, 'M', 'mardi'],
  [3, 'M', 'mercredi'],
  [4, 'J', 'jeudi'],
  [5, 'V', 'vendredi'],
  [6, 'S', 'samedi'],
  [7, 'D', 'dimanche'],
]

/** « Horaires de travail » (MAG-790): start, end and a pill per day. Maggie's tone follows them. */
const WorkHoursCard = ({
  start,
  end,
  days,
  onSave,
}: {
  start: string
  end: string
  days: number[]
  onSave: (patch: RendezvousPatch) => Promise<boolean>
}) => {
  const [startDraft, setStartDraft] = useState(start)
  useEffect(() => setStartDraft(start), [start])
  const [endDraft, setEndDraft] = useState(end)
  useEffect(() => setEndDraft(end), [end])
  const [dayDraft, setDayDraft] = useState(days)
  useEffect(() => setDayDraft(days), [days])

  // A time field sends a change per segment typed (« 1 » then « 17 »): a value out of order is kept as typed and
  // only not saved, so the next keystroke can still reach a valid hour.
  const [outOfOrder, setOutOfOrder] = useState(false)

  const handleStart = async (value: string) => {
    setStartDraft(value)
    if (!value) return
    const refused = endDraft <= value
    setOutOfOrder(refused)
    if (refused || value === start) return
    if (!(await onSave({ workStartTime: value }))) setStartDraft(start)
  }

  const handleEnd = async (value: string) => {
    setEndDraft(value)
    if (!value) return
    const refused = value <= startDraft
    setOutOfOrder(refused)
    if (refused || value === end) return
    if (!(await onSave({ workEndTime: value }))) setEndDraft(end)
  }

  const handleDays = async (value: number[]) => {
    const next = [...value].sort((a, b) => a - b)
    setDayDraft(next)
    if (!(await onSave({ workDays: next }))) setDayDraft(days)
  }

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Stack spacing={1.5}>
          <Typography variant="subtitle1">Horaires de travail</Typography>
          <Typography variant="body2" color="text.secondary">
            Maggie reste professionnelle pendant, plus familière en dehors.
          </Typography>
          <Stack direction="row" spacing={2}>
            <TextField
              type="time"
              label="Début du travail"
              value={startDraft}
              onChange={(e) => handleStart(e.target.value)}
              size="small"
              sx={{ maxWidth: 160 }}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              type="time"
              label="Fin du travail"
              value={endDraft}
              onChange={(e) => handleEnd(e.target.value)}
              error={outOfOrder}
              helperText={outOfOrder ? 'La fin doit suivre le début' : undefined}
              size="small"
              sx={{ maxWidth: 160 }}
              slotProps={{ inputLabel: { shrink: true } }}
            />
          </Stack>
          <ToggleButtonGroup
            value={dayDraft}
            onChange={(_, value: number[]) => handleDays(value)}
            aria-label="Jours de travail"
            size="small"
          >
            {WORK_DAYS.map(([day, letter, name]) => (
              <ToggleButton key={day} value={day} aria-label={name}>
                {letter}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
        </Stack>
      </CardContent>
    </Card>
  )
}

/**
 * Réglages › Maggie (MAG-788): the morning digest and the evening review, each a switch and an hour, saved as they
 * change; then the working hours (MAG-790).
 */
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
      <WorkHoursCard
        start={preferences.workStartTime}
        end={preferences.workEndTime}
        days={preferences.workDays}
        onSave={save}
      />
    </>
  )
}
