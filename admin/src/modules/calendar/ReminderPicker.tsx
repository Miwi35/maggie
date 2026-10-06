import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/DeleteOutline'

/** Google's shape, which is the one the API stores and the reminder cron reads. */
export interface EventReminders {
  useDefault: boolean
  overrides: { method: string; minutes: number }[]
}

interface ReminderPickerProps {
  value: EventReminders | null
  onChange: (reminders: EventReminders | null) => void
}

/** The delays offered, in minutes before the start. */
const DELAYS: { minutes: number; label: string }[] = [
  { minutes: 5, label: '5 minutes avant' },
  { minutes: 10, label: '10 minutes avant' },
  { minutes: 15, label: '15 minutes avant' },
  { minutes: 30, label: '30 minutes avant' },
  { minutes: 60, label: '1 heure avant' },
  { minutes: 120, label: '2 heures avant' },
  { minutes: 1440, label: '1 jour avant' },
  { minutes: 2880, label: '2 jours avant' },
  { minutes: 10080, label: '1 semaine avant' },
]

/** As many as Google accepts on one event, and as many as the API validates. */
const MAX_REMINDERS = 5

const DEFAULT_DELAY = 30

const labelOf = (minutes: number): string =>
  DELAYS.find((delay) => delay.minutes === minutes)?.label ?? `${minutes} minutes avant`

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
 * The reminders of an event: a row per delay, added and removed one at a time.
 *
 * Until MAG-121 nothing but Google's import could fill this field, so a reminder
 * could be read on screen and never set. What the picker emits is the shape the
 * API stores — the conversion lives here so no dialog builds it by hand, and a
 * bare list of reminders is a reminder that fires nothing.
 *
 * Only delays are offered, and every one is a `popup`: Maggie notifies in the app,
 * so an `email` reminder imported from Google becomes a popup the first time the
 * owner touches that event's reminders — the API still accepts both.
 *
 * Controlled with no state of its own: the whole state is the list of delays, so
 * there is nothing to keep in sync with the dialog that owns it. A delay already
 * chosen is disabled in the other rows rather than deduplicated on submit — the
 * row the owner just picked never disappears under him.
 */
export const ReminderPicker = ({ value, onChange }: ReminderPickerProps) => {
  const minutes = remindersToMinutes(value)
  const emit = (next: number[]) => onChange(remindersFromMinutes(next))

  const free = DELAYS.find((delay) => !minutes.includes(delay.minutes))?.minutes
  const nextDelay = minutes.includes(DEFAULT_DELAY) ? free : DEFAULT_DELAY

  return (
    <Box>
      <Typography variant="body2" sx={{ mb: minutes.length > 0 ? 1 : 0 }}>
        Rappels
      </Typography>

      {minutes.length === 0 && (
        <Typography variant="caption" color="text.secondary">
          Aucun rappel
        </Typography>
      )}

      {minutes.map((chosen, index) => (
        <Box key={index} sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
          <TextField
            label="Rappel"
            value={chosen}
            onChange={(e) =>
              emit(minutes.map((m, i) => (i === index ? Number(e.target.value) : m)))
            }
            select
            size="small"
            fullWidth
          >
            {DELAYS.map((delay) => (
              <MenuItem
                key={delay.minutes}
                value={delay.minutes}
                disabled={delay.minutes !== chosen && minutes.includes(delay.minutes)}
              >
                {delay.label}
              </MenuItem>
            ))}
          </TextField>
          <IconButton
            aria-label={`Supprimer le rappel ${labelOf(chosen)}`}
            onClick={() => emit(minutes.filter((_, i) => i !== index))}
            size="small"
          >
            <DeleteIcon fontSize="small" />
          </IconButton>
        </Box>
      ))}

      {minutes.length < MAX_REMINDERS && nextDelay !== undefined && (
        <Button
          startIcon={<AddIcon />}
          onClick={() => emit([...minutes, nextDelay])}
          size="small"
          sx={{ mt: minutes.length > 0 ? 0 : 1 }}
        >
          Ajouter un rappel
        </Button>
      )}
    </Box>
  )
}
