import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Popover from '@mui/material/Popover'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import LocationOnOutlinedIcon from '@mui/icons-material/LocationOnOutlined'
import NotesOutlinedIcon from '@mui/icons-material/NotesOutlined'
import NotificationsOutlinedIcon from '@mui/icons-material/NotificationsOutlined'
import RepeatIcon from '@mui/icons-material/Repeat'
import ScheduleIcon from '@mui/icons-material/Schedule'
import Chip from '@mui/material/Chip'
import { lastDayOf, parseDay } from '../../dates'
import { rruleToFrenchText } from './recurrenceUtils'
import { remindersToFrenchText } from './ReminderPicker'
import type { EventReminders } from './ReminderPicker'
import type { EventStatus } from './eventStatus'

export interface PopoverEvent {
  id: string
  title: string
  /** An instant (ISO 8601), or for an all-day event its first date, `YYYY-MM-DD`. */
  start: string
  /** An instant (ISO 8601), or for an all-day event its end date, excluded as stored (MAG-382). */
  end: string
  allDay: boolean
  color: string
  calendarName: string
  description?: string
  location?: string
  rrule?: string
  reminders?: EventReminders | null
  status?: EventStatus
  masterEventId?: string
  isVirtualOccurrence?: boolean
  calendarIri?: string
}

interface EventDetailPopoverProps {
  event: PopoverEvent | null
  anchorEl: HTMLElement | null
  onClose: () => void
  onEdit: (eventId: string) => void
  onDelete: (eventId: string) => void
}

const capitalize = (s: string): string => s.charAt(0).toUpperCase() + s.slice(1)

const formatDateTime = (start: string, end: string, allDay: boolean): string => {
  const dateOpts: Intl.DateTimeFormatOptions = {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }

  if (allDay) {
    // Shown as Google shows it, the last day included (« au 28 »), from the stored
    // exclusive end — never through an instant a time zone could move (MAG-358, MAG-382).
    const firstDay = start.slice(0, 10)
    const lastDay = end ? lastDayOf(end.slice(0, 10)) : firstDay
    const startStr = parseDay(firstDay).toLocaleDateString('fr-FR', dateOpts)
    if (lastDay <= firstDay) {
      return capitalize(startStr)
    }
    return `${capitalize(startStr)} – ${parseDay(lastDay).toLocaleDateString('fr-FR', dateOpts)}`
  }

  const startDate = new Date(start)
  const endDate = new Date(end)
  const dateStr = startDate.toLocaleDateString('fr-FR', dateOpts)
  const timeOpts: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', hour12: false }
  const startTime = startDate.toLocaleTimeString('fr-FR', timeOpts)
  const endTime = endDate.toLocaleTimeString('fr-FR', timeOpts)

  return `${capitalize(dateStr)}, ${startTime} – ${endTime}`
}

export const EventDetailPopover = ({
  event,
  anchorEl,
  onClose,
  onEdit,
  onDelete,
}: EventDetailPopoverProps) => {
  if (!event) return null

  return (
    <Popover
      open={Boolean(anchorEl)}
      anchorEl={anchorEl}
      onClose={onClose}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      transformOrigin={{ vertical: 'top', horizontal: 'left' }}
      slotProps={{
        paper: {
          sx: {
            borderRadius: 2,
            minWidth: 300,
            maxWidth: 400,
            boxShadow: 8,
          },
        },
      }}
    >
      {/* Header with actions */}
      <Box sx={{ display: 'flex', justifyContent: 'flex-end', px: 1, pt: 0.5 }}>
        <IconButton size="small" aria-label="Modifier" onClick={() => onEdit(event.id)}>
          <EditOutlinedIcon fontSize="small" />
        </IconButton>
        <IconButton
          size="small"
          aria-label="Supprimer"
          onClick={() => onDelete(event.id)}
        >
          <DeleteOutlineIcon fontSize="small" />
        </IconButton>
        <IconButton size="small" onClick={onClose} aria-label="Fermer">
          <CloseIcon fontSize="small" />
        </IconButton>
      </Box>

      {/* Content */}
      <Box sx={{ px: 2.5, pb: 2.5 }}>
        {/* Title */}
        <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 1.5 }}>
          <Box
            sx={{
              width: 14,
              height: 14,
              borderRadius: '3px',
              backgroundColor: event.color,
              flexShrink: 0,
              mt: 0.5,
            }}
          />
          <Typography variant="h6" sx={{ fontSize: '1.1rem', fontWeight: 500, lineHeight: 1.3 }}>
            {event.title}
          </Typography>
        </Box>

        {event.status === 'tentative' && (
          <Chip label="Provisoire" size="small" variant="outlined" sx={{ mb: 1.5, borderStyle: 'dashed' }} />
        )}

        {/* Date/Time */}
        <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 1 }}>
          <ScheduleIcon sx={{ fontSize: 18, color: 'text.secondary', mt: 0.2 }} />
          <Typography variant="body2" color="text.secondary">
            {formatDateTime(event.start, event.end, event.allDay)}
          </Typography>
        </Box>

        {/* Recurrence */}
        {event.rrule && (
          <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 1 }}>
            <RepeatIcon sx={{ fontSize: 18, color: 'text.secondary', mt: 0.2 }} />
            <Typography variant="body2" color="text.secondary">
              {rruleToFrenchText(event.rrule)}
            </Typography>
          </Box>
        )}

        {/* Reminders — the only place the owner can check what he will be told, and when */}
        {remindersToFrenchText(event.reminders) && (
          <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 1 }}>
            <NotificationsOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', mt: 0.2 }} />
            <Typography variant="body2" color="text.secondary">
              {remindersToFrenchText(event.reminders)}
            </Typography>
          </Box>
        )}

        {/* Location */}
        {event.location && (
          <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 1 }}>
            <LocationOnOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', mt: 0.2 }} />
            <Typography variant="body2" color="text.secondary">
              {event.location}
            </Typography>
          </Box>
        )}

        {/* Description */}
        {event.description && (
          <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 1 }}>
            <NotesOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', mt: 0.2 }} />
            <Typography
              variant="body2"
              color="text.secondary"
              sx={{
                whiteSpace: 'pre-wrap',
                maxHeight: 80,
                overflow: 'auto',
              }}
            >
              {event.description}
            </Typography>
          </Box>
        )}

        {/* Calendar name */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 1.5 }}>
          <Box
            sx={{
              width: 10,
              height: 10,
              borderRadius: '50%',
              backgroundColor: event.color,
              flexShrink: 0,
            }}
          />
          <Typography variant="body2" color="text.secondary">
            {event.calendarName}
          </Typography>
        </Box>
      </Box>
    </Popover>
  )
}
