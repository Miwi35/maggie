import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import LocationOnOutlinedIcon from '@mui/icons-material/LocationOnOutlined'
import NotesOutlinedIcon from '@mui/icons-material/NotesOutlined'
import NotificationsOutlinedIcon from '@mui/icons-material/NotificationsOutlined'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import ScheduleIcon from '@mui/icons-material/Schedule'
import type { ReactNode } from 'react'
import { Link as RouterLink, useLocation } from 'react-router-dom'
import { REVIEW_ACTIVITY, REVIEW_ACTIVITY_PATH, eventTitle } from '../../finance/reviewActivity'
import type { ReviewLaunchState } from '../../finance/reviewActivity'
import { remindersToFrenchText } from '../../calendar/ReminderPicker'
import { scheduleLabel } from './agendaTimeline'
import type { AgendaHomeEvent } from './agendaTimeline'

/** The day an event falls on, for the review's title: its first day, or the day of its start. */
const startOf = (event: AgendaHomeEvent): string => event.startDate ?? event.startAt ?? ''

const Line = ({ icon, children }: { icon: ReactNode; children: ReactNode }) => (
  <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 1.5 }}>
    <Box aria-hidden sx={{ display: 'flex', color: 'text.secondary', mt: 0.25 }}>
      {icon}
    </Box>
    {children}
  </Box>
)

/** « Lancer »: the review activity, full screen; quitting it comes back to the Accueil as it was (MAG-456). */
const LaunchButton = () => {
  const location = useLocation()
  const state: ReviewLaunchState = { from: `${location.pathname}${location.search}` }

  return (
    <Button component={RouterLink} to={REVIEW_ACTIVITY_PATH} state={state} variant="contained" startIcon={<PlayArrowIcon />}>
      Lancer
    </Button>
  )
}

interface AgendaEventPanelProps {
  event: AgendaHomeEvent | null
  onClose: () => void
}

/**
 * The fiche of an event touched on the Accueil: a panel on the right, read only.
 * Editing is the Agenda's job — « Ouvrir dans l'agenda » goes there.
 */
export const AgendaEventPanel = ({ event, onClose }: AgendaEventPanelProps) => {
  const reminder = event ? remindersToFrenchText(event.reminders) : null
  const title = event ? eventTitle(event.summary, event.activity, startOf(event)) : ''

  return (
    <Drawer
      anchor="right"
      open={event !== null}
      onClose={onClose}
      slotProps={{ paper: { sx: { width: { xs: '100%', sm: 400 }, maxWidth: '100%' } } }}
    >
      {event && (
        <Box role="region" aria-label={`Fiche : ${title}`} data-testid="agenda-event-panel" sx={{ p: 2.5 }}>
          <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.5, mb: 2 }}>
            <Box
              aria-hidden
              sx={{ width: 14, height: 14, borderRadius: '3px', bgcolor: event.agendaColor ?? 'primary.main', flexShrink: 0, mt: 0.75 }}
            />
            <Typography variant="h6" component="h2" sx={{ flex: 1, fontSize: '1.15rem', lineHeight: 1.3 }}>
              {title}
            </Typography>
            <IconButton size="small" aria-label="Fermer" onClick={onClose}>
              <CloseIcon fontSize="small" />
            </IconButton>
          </Box>

          <Line icon={<ScheduleIcon sx={{ fontSize: 18 }} />}>
            <Typography variant="body2">{scheduleLabel(event)}</Typography>
          </Line>

          {event.location && (
            <Line icon={<LocationOnOutlinedIcon sx={{ fontSize: 18 }} />}>
              <Typography variant="body2">{event.location}</Typography>
            </Line>
          )}

          <Line
            icon={<Box sx={{ width: 10, height: 10, mx: '4px', mt: '5px', borderRadius: '50%', bgcolor: event.agendaColor ?? 'primary.main' }} />}
          >
            <Typography variant="body2">{event.agendaName}</Typography>
          </Line>

          {event.description && (
            <Line icon={<NotesOutlinedIcon sx={{ fontSize: 18 }} />}>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
                {event.description}
              </Typography>
            </Line>
          )}

          {reminder && (
            <Line icon={<NotificationsOutlinedIcon sx={{ fontSize: 18 }} />}>
              <Typography variant="body2">{reminder}</Typography>
            </Line>
          )}

          <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mt: 2 }}>
            {event.activity === REVIEW_ACTIVITY && <LaunchButton />}
            <Button component={RouterLink} to={`/calendar?eventId=${encodeURIComponent(`/api/events/${event.id}`)}`} variant="outlined">
              Ouvrir dans l'agenda
            </Button>
          </Box>
        </Box>
      )}
    </Drawer>
  )
}
