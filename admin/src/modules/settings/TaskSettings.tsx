import { useState } from 'react'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { urgencyColor } from '../../design/tokens'
import { URGENCIES, useTaskSettings } from './taskSettingsApi'
import type { TaskSettingsResponse, Urgency, UrgencySchedule } from './taskSettingsApi'
import { DefaultsButton, TaskSettingsFrame } from './TaskSettingsParts'

/**
 * Réglages › Tâches (MAG-407): when Maggie alerts about a task, by urgency.
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
const TimeField = ({
  id,
  label,
  saved,
  onSave,
}: {
  id: string
  label: string
  saved: string
  onSave: (time: string) => void
}) => {
  const [value, setValue] = useState(saved)

  const commit = () => {
    if (!TIME.test(value)) setValue(saved)
    else if (value !== saved) onSave(value)
  }

  return (
    <Stack direction="row" alignItems="center" spacing={2}>
      <Typography component="label" htmlFor={id} variant="subtitle1" sx={{ minWidth: 130 }}>
        {label}
      </Typography>
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
      />
    </Stack>
  )
}

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
