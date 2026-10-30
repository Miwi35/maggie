import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import ListItemText from '@mui/material/ListItemText'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'

/** Google's shape, which is the one the API stores and the reminder cron reads. */
export interface EventReminders {
  useDefault: boolean
  overrides: { method: string; minutes: number }[]
}

interface ReminderPickerProps {
  value: EventReminders | null
  onChange: (reminders: EventReminders | null) => void
}

/** The delays offered, in minutes before the start — Google Agenda's, then « Personnaliser… ». */
const PRESETS = [5, 10, 15, 30, 60, 1440]

/** As many as Google accepts on one event, and as many as the API validates. */
const MAX_REMINDERS = 5

const CUSTOM = 'custom'

const UNITS = [
  { key: 'minutes', factor: 1, label: 'minutes' },
  { key: 'hours', factor: 60, label: 'heures' },
  { key: 'days', factor: 1440, label: 'jours' },
]

/** « 5 minutes avant », « 1 heure avant », « 2 jours avant ». */
const labelOf = (minutes: number): string => {
  const [value, one, many] =
    minutes % 1440 === 0
      ? [minutes / 1440, 'jour', 'jours']
      : minutes % 60 === 0
        ? [minutes / 60, 'heure', 'heures']
        : [minutes, 'minute', 'minutes']

  return `${value} ${value === 1 ? one : many} avant`
}

/** The delays an event holds. Anything the cron would ignore reads as no reminder. */
export const remindersToMinutes = (reminders: EventReminders | null | undefined): number[] =>
  (reminders?.overrides ?? []).map((override) => override.minutes).filter((minutes) => minutes > 0)

/** The reminders of an event in French, or null when it has none. */
export const remindersToFrenchText = (reminders: EventReminders | null | undefined): string | null => {
  const minutes = remindersToMinutes(reminders)

  return minutes.length === 0 ? null : minutes.map(labelOf).join(', ')
}

/** Google's shape for a list of delays, or null — which is what clears the field. */
export const remindersFromMinutes = (minutes: number[]): EventReminders | null =>
  minutes.length === 0
    ? null
    : { useDefault: false, overrides: minutes.map((m) => ({ method: 'popup', minutes: m })) }

/**
 * The reminders of an event: a menu of checkable delays, several at once, plus a
 * free delay through « Personnaliser… » (MAG-724).
 *
 * What the picker emits is the shape the API stores — the conversion lives here so
 * no dialog builds it by hand, and a bare list of reminders fires nothing. Every
 * reminder is a `popup`: Maggie notifies in the app.
 *
 * Controlled with no state of its own beyond the custom-delay row: the list of
 * delays is the whole value. A delay outside the presets (custom, or imported from
 * Google) stays in the menu, checked, so it can be unchecked.
 */
export const ReminderPicker = ({ value, onChange }: ReminderPickerProps) => {
  const minutes = remindersToMinutes(value)
  const emit = (next: number[]) => onChange(remindersFromMinutes(next))

  const [menuOpen, setMenuOpen] = useState(false)
  const [customOpen, setCustomOpen] = useState(false)
  const [amount, setAmount] = useState('1')
  const [unit, setUnit] = useState('hours')

  const options = [...PRESETS, ...minutes.filter((m) => !PRESETS.includes(m))]
  const full = minutes.length >= MAX_REMINDERS

  const addCustom = () => {
    const total = Math.round(Number(amount) * (UNITS.find((u) => u.key === unit)?.factor ?? 1))
    if (!(total > 0) || minutes.includes(total)) return
    emit([...minutes, total])
    setCustomOpen(false)
  }

  return (
    <Box>
      <TextField
        label="Rappels"
        select
        fullWidth
        value={minutes}
        onChange={(e) => {
          const chosen = e.target.value as unknown as (number | string)[]
          if (chosen.includes(CUSTOM)) {
            setCustomOpen(true)
            setMenuOpen(false)
            return
          }
          emit(chosen as number[])
        }}
        slotProps={{
          select: {
            multiple: true,
            open: menuOpen,
            onOpen: () => setMenuOpen(true),
            onClose: () => setMenuOpen(false),
            displayEmpty: true,
            renderValue: (selected) =>
              (selected as number[]).length === 0 ? 'Aucun rappel' : (selected as number[]).map(labelOf).join(', '),
          },
          inputLabel: { shrink: true },
        }}
      >
        {options.map((m) => (
          <MenuItem key={m} value={m} disabled={full && !minutes.includes(m)}>
            <Checkbox checked={minutes.includes(m)} size="small" tabIndex={-1} disableRipple />
            <ListItemText primary={labelOf(m)} />
          </MenuItem>
        ))}
        <MenuItem value={CUSTOM} disabled={full}>
          <ListItemText primary="Personnaliser…" />
        </MenuItem>
      </TextField>

      {customOpen && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1 }}>
          <TextField
            label="Délai"
            type="number"
            size="small"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            slotProps={{ htmlInput: { min: 1 } }}
          />
          <TextField
            label="Unité"
            select
            size="small"
            value={unit}
            onChange={(e) => setUnit(e.target.value)}
          >
            {UNITS.map((u) => (
              <MenuItem key={u.key} value={u.key}>
                {u.label}
              </MenuItem>
            ))}
          </TextField>
          <Button onClick={addCustom} size="small" variant="outlined">
            Ajouter
          </Button>
        </Box>
      )}
    </Box>
  )
}
