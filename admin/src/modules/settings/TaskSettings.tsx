import { useState } from 'react'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import { urgencyColor } from '../../design/tokens'
import { DAY_MOMENTS, REMINDER_CAP_MAX, REMINDER_CAP_MIN, TONES, URGENCIES, useTaskSettings } from './taskSettingsApi'
import type { DayMoment, SettingsPatch, TaskSettingsResponse, Tone, Urgency, UrgencySchedule } from './taskSettingsApi'
import { DefaultsButton, TaskSettingsFrame } from './TaskSettingsParts'

/**
 * Réglages › Tâches (MAG-407): when Maggie alerts about a task, by urgency,
 * and how a task done is cheered (MAG-508).
 * Every change is saved at once; the task order lives on its own page
 * (`TaskOrderSettings`).
 */

const URGENCY_LABELS: Record<Urgency, string> = {
  critical: 'Critique',
  high: 'Haute',
  normal: 'Normale',
}

/** The days the chips offer; any other `daysBefore` value (set through the API) is kept as is. */
const DAY_CHIPS = [7, 3, 2, 1] as const

const OVERDUE_CHOICES: [number | null, string][] = [
  [null, 'Jamais'],
  [1, 'Chaque matin'],
  [2, 'Tous les 2 jours'],
  [7, 'Chaque semaine'],
]

const TONE_LABELS: Record<Tone, string> = {
  doux: 'Doux',
  direct: 'Direct',
  drole: 'Drôle',
}

type RewardToggle = 'rewardAnimation' | 'rewardVibration' | 'rewardSound'

const REWARD_TOGGLES: [RewardToggle, string][] = [
  ['rewardAnimation', 'Animation'],
  ['rewardVibration', 'Vibration'],
  ['rewardSound', 'Son'],
]

const MOMENT_LABELS: Record<DayMoment, string> = {
  wake: 'Réveil',
  afterBreakfast: 'Après le petit-déjeuner',
  afterLunch: 'Après le déjeuner',
  afterDinner: 'Après le dîner',
}

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/

const withSchedule =
  (urgency: Urgency, patch: Partial<UrgencySchedule>) =>
  (s: TaskSettingsResponse): TaskSettingsResponse => ({
    ...s,
    alertSchedule: { ...s.alertSchedule, [urgency]: { ...s.alertSchedule[urgency], ...patch } },
  })

const DayChip = ({
  label,
  name,
  selected,
  color,
  onToggle,
}: {
  label: string
  name: string
  selected: boolean
  color: string
  onToggle: () => void
}) => (
  <Chip
    label={label}
    aria-label={name}
    aria-pressed={selected}
    clickable
    onClick={onToggle}
    variant={selected ? 'filled' : 'outlined'}
    sx={(theme) =>
      selected
        ? {
            bgcolor: color,
            color: theme.palette.getContrastText(color),
            '&:hover': { bgcolor: color },
          }
        : {}
    }
  />
)

/**
 * The time, saved when the field is left (or on Enter) and only as a whole
 * `HH:MM`: a browser's time field is valid after each keystroke (07:00 on the
 * way to 07:30), so saving on change would send every step. A value that is
 * not a time goes back to the saved one.
 */
const TimeInput = ({
  id,
  name,
  saved,
  onSave,
}: {
  id?: string
  /** The accessible name, when no visible label points at the field. */
  name?: string
  saved: string
  onSave: (time: string) => void
}) => {
  const [value, setValue] = useState(saved)

  const commit = () => {
    if (!TIME.test(value)) setValue(saved)
    else if (value !== saved) onSave(value)
  }

  return (
    <TextField
      id={id}
      type="time"
      size="small"
      value={value}
      onChange={(e) => setValue(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') commit()
      }}
      slotProps={name ? { htmlInput: { 'aria-label': name } } : undefined}
    />
  )
}

const TimeField = ({
  id,
  label,
  saved,
  onSave,
  labelWidth = 130,
}: {
  id: string
  label: string
  saved: string
  onSave: (time: string) => void
  labelWidth?: number
}) => (
  <Stack direction="row" alignItems="center" spacing={2}>
    <Typography component="label" htmlFor={id} variant="subtitle1" sx={{ minWidth: labelWidth }}>
      {label}
    </Typography>
    <TimeInput id={id} saved={saved} onSave={onSave} />
  </Stack>
)

const UrgencyBlock = ({
  urgency,
  schedule,
  onChange,
}: {
  urgency: Urgency
  schedule: UrgencySchedule
  onChange: (patch: Partial<UrgencySchedule>) => void
}) => {
  const label = URGENCY_LABELS[urgency]
  const color = urgencyColor(urgency)

  const toggleDay = (day: number) => {
    const days = schedule.daysBefore.includes(day)
      ? schedule.daysBefore.filter((d) => d !== day)
      : [...schedule.daysBefore, day]
    onChange({ daysBefore: [...days].sort((a, b) => b - a) })
  }

  const overdue = schedule.overdueEveryDays
  const choices =
    overdue === null || OVERDUE_CHOICES.some(([v]) => v === overdue)
      ? OVERDUE_CHOICES
      : [...OVERDUE_CHOICES, [overdue, `Tous les ${overdue} jours`] as [number, string]]

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack direction="row" alignItems="center" spacing={1} mb={2}>
          <Box aria-hidden sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: color }} />
          <Typography variant="subtitle1" component="h2" fontWeight={600}>
            {label}
          </Typography>
        </Stack>

        <Stack spacing={2}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 1, sm: 2 }} alignItems={{ sm: 'center' }}>
            <Typography variant="body2" sx={{ minWidth: 170 }}>
              Jours avant l'échéance
            </Typography>
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              {DAY_CHIPS.map((day) => (
                <DayChip
                  key={day}
                  label={`J-${day}`}
                  name={`${label} — J-${day}`}
                  selected={schedule.daysBefore.includes(day)}
                  color={color}
                  onToggle={() => toggleDay(day)}
                />
              ))}
              <DayChip
                label="Jour J"
                name={`${label} — Jour J`}
                selected={schedule.dueDay}
                color={color}
                onToggle={() => onChange({ dueDay: !schedule.dueDay })}
              />
            </Stack>
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 1, sm: 2 }} alignItems={{ sm: 'center' }}>
            <Typography variant="body2" sx={{ minWidth: 170 }}>
              En retard
            </Typography>
            <TextField
              select
              size="small"
              value={overdue === null ? '' : String(overdue)}
              onChange={(e) => onChange({ overdueEveryDays: e.target.value === '' ? null : Number(e.target.value) })}
              slotProps={{ select: { native: true }, htmlInput: { 'aria-label': `${label} — en retard` } }}
              sx={{ minWidth: 200 }}
            >
              {choices.map(([value, text]) => (
                <option key={text} value={value === null ? '' : String(value)}>
                  {text}
                </option>
              ))}
            </TextField>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  )
}

/** « Au plus, par jour »: − and + around the number, and as many dots lit out of twelve. */
const CapStepper = ({ cap, onChange }: { cap: number; onChange: (cap: number) => void }) => (
  <Stack direction="row" alignItems="center" spacing={2} flexWrap="wrap" useFlexGap>
    <Typography id="task-settings-cap" variant="body2" sx={{ minWidth: 200 }}>
      Au plus, par jour
    </Typography>
    <Stack direction="row" alignItems="center" spacing={1} role="group" aria-labelledby="task-settings-cap">
      <IconButton
        size="small"
        aria-label="Moins"
        disabled={cap <= REMINDER_CAP_MIN}
        onClick={() => onChange(cap - 1)}
      >
        <RemoveIcon fontSize="small" />
      </IconButton>
      <Typography variant="subtitle1" fontWeight={600} sx={{ minWidth: 24, textAlign: 'center' }}>
        {cap}
      </Typography>
      <IconButton size="small" aria-label="Plus" disabled={cap >= REMINDER_CAP_MAX} onClick={() => onChange(cap + 1)}>
        <AddIcon fontSize="small" />
      </IconButton>
    </Stack>
    <Stack direction="row" spacing={0.5} aria-hidden>
      {Array.from({ length: REMINDER_CAP_MAX }, (_, i) => (
        <Box
          key={i}
          sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: i < cap ? 'primary.main' : 'action.disabled' }}
        />
      ))}
    </Stack>
  </Stack>
)

/**
 * « Rappels et récompenses »: the bravo of a task done, the tone of the words
 * (MAG-508), and when the reminders come — how many, never in the quiet hours,
 * anchored to the moments of the day (MAG-510).
 */
const RewardBlock = ({
  settings,
  onChange,
}: {
  settings: TaskSettingsResponse
  onChange: (patch: SettingsPatch, change: (s: TaskSettingsResponse) => TaskSettingsResponse) => void
}) => {
  const set = (patch: Pick<SettingsPatch, RewardToggle | 'tone' | 'reminderDailyCap' | 'quietStart' | 'quietEnd'>) =>
    onChange(patch, (s) => ({ ...s, ...patch }))
  const setMoment = (moment: DayMoment, time: string) =>
    onChange({ dayMoments: { [moment]: time } }, (s) => ({ ...s, dayMoments: { ...s.dayMoments, [moment]: time } }))

  return (
    <Card variant="outlined" component="section" aria-labelledby="task-settings-rewards">
      <CardContent>
        <Typography id="task-settings-rewards" variant="subtitle1" component="h2" fontWeight={600} mb={1}>
          Rappels et récompenses
        </Typography>
        <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
          {REWARD_TOGGLES.map(([field, label]) => (
            <FormControlLabel
              key={field}
              control={<Switch checked={settings[field]} onChange={(e) => set({ [field]: e.target.checked })} />}
              label={label}
            />
          ))}
        </Stack>
        <Stack direction="row" alignItems="center" spacing={2} mt={2}>
          <Typography variant="body2" sx={{ minWidth: 60 }}>
            Ton
          </Typography>
          <ToggleButtonGroup
            exclusive
            size="small"
            value={settings.tone}
            aria-label="Ton"
            onChange={(_, tone: Tone | null) => {
              if (tone) set({ tone })
            }}
          >
            {TONES.map((tone) => (
              <ToggleButton key={tone} value={tone}>
                {TONE_LABELS[tone]}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
        </Stack>

        <Stack spacing={2} mt={3}>
          <CapStepper cap={settings.reminderDailyCap} onChange={(reminderDailyCap) => set({ reminderDailyCap })} />

          <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap>
            <Typography variant="body2" sx={{ minWidth: 200 }}>
              Calme
            </Typography>
            <TimeInput
              key={`quiet-start-${settings.quietStart}`}
              name="Calme de"
              saved={settings.quietStart}
              onSave={(quietStart) => set({ quietStart })}
            />
            <Typography variant="body2" aria-hidden>
              –
            </Typography>
            <TimeInput
              key={`quiet-end-${settings.quietEnd}`}
              name="Calme jusqu'à"
              saved={settings.quietEnd}
              onSave={(quietEnd) => set({ quietEnd })}
            />
          </Stack>

          <Typography variant="body2" component="h3" fontWeight={600}>
            Mes moments
          </Typography>
          {DAY_MOMENTS.map((moment) => (
            <TimeField
              key={`${moment}-${settings.dayMoments[moment]}`}
              id={`task-settings-moment-${moment}`}
              label={MOMENT_LABELS[moment]}
              labelWidth={200}
              saved={settings.dayMoments[moment]}
              onSave={(time) => setMoment(moment, time)}
            />
          ))}
        </Stack>
      </CardContent>
    </Card>
  )
}

export const TaskSettings = () => {
  const { settings, loadFailed, saved, save } = useTaskSettings()

  return (
    <TaskSettingsFrame title="Tâches" loadFailed={loadFailed} ready={settings !== null} saved={saved}>
      {settings && (
        <Stack spacing={3}>
          <TimeField
            key={`morning-${settings.morningTime}`}
            id="task-settings-morning-time"
            label="Heure du matin"
            saved={settings.morningTime}
            onSave={(morningTime) => save({ morningTime }, (s) => ({ ...s, morningTime }))}
          />
          <TimeField
            key={`evening-${settings.eveningTime}`}
            id="task-settings-evening-time"
            label="Heure du soir"
            saved={settings.eveningTime}
            onSave={(eveningTime) => save({ eveningTime }, (s) => ({ ...s, eveningTime }))}
          />

          {URGENCIES.map((urgency) => (
            <UrgencyBlock
              key={urgency}
              urgency={urgency}
              schedule={settings.alertSchedule[urgency]}
              onChange={(patch) => save({ alertSchedule: { [urgency]: patch } }, withSchedule(urgency, patch))}
            />
          ))}

          <RewardBlock settings={settings} onChange={save} />

          {/* The rewards and reminders keep theirs: a sound turned off, a quiet evening, do not come back with the alerts. */}
          <DefaultsButton
            onConfirm={() => {
              const { alertSchedule, morningTime, eveningTime } = settings.defaults
              save({ alertSchedule, morningTime, eveningTime }, (s) => ({ ...s, alertSchedule, morningTime, eveningTime }))
            }}
          />
        </Stack>
      )}
    </TaskSettingsFrame>
  )
}
