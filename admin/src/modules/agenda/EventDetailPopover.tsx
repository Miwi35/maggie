import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Popover from '@mui/material/Popover'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import LocationOnOutlinedIcon from '@mui/icons-material/LocationOnOutlined'
import NotesOutlinedIcon from '@mui/icons-material/NotesOutlined'
import ScheduleIcon from '@mui/icons-material/Schedule'

export interface PopoverEvent {
  id: string
  title: string
  start: string
  end: string
  allDay: boolean
  color: string
  calendarName: string
  description?: string
  location?: string
  status?: string
}

interface EventDetailPopoverProps {
  event: PopoverEvent | null
  anchorEl: HTMLElement | null
  onClose: () => void
  onDelete: (eventId: string) => void
}

const formatDateTime = (start: string, end: string, allDay: boolean): string => {
  const startDate = new Date(start)
  const endDate = new Date(end)

  const dateOpts: Intl.DateTimeFormatOptions = {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }

  if (allDay) {
    const startStr = startDate.toLocaleDateString('fr-FR', dateOpts)
    if (startDate.toDateString() === endDate.toDateString()) {
      return startStr.charAt(0).toUpperCase() + startStr.slice(1)
    }
    const endStr = endDate.toLocaleDateString('fr-FR', dateOpts)
    return `${startStr.charAt(0).toUpperCase() + startStr.slice(1)} – ${endStr}`
  }

  const dateStr = startDate.toLocaleDateString('fr-FR', dateOpts)
  const timeOpts: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', hour12: false }
  const startTime = startDate.toLocaleTimeString('fr-FR', timeOpts)
  const endTime = endDate.toLocaleTimeString('fr-FR', timeOpts)

  return `${dateStr.charAt(0).toUpperCase() + dateStr.slice(1)}, ${startTime} – ${endTime}`
}

export const EventDetailPopover = ({
  event,
  anchorEl,
  onClose,
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
        <IconButton size="small" aria-label="Modifier">
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

        {/* Date/Time */}
        <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 1 }}>
          <ScheduleIcon sx={{ fontSize: 18, color: 'text.secondary', mt: 0.2 }} />
          <Typography variant="body2" color="text.secondary">
            {formatDateTime(event.start, event.end, event.allDay)}
          </Typography>
        </Box>

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
