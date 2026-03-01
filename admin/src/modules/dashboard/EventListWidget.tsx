import { useCallback, useMemo } from 'react'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'
import LocationOnOutlinedIcon from '@mui/icons-material/LocationOnOutlined'
import { useItemTransitions, transitionSx } from '../../hooks/useItemTransitions'

export interface DashboardEvent {
  id: string
  summary: string
  startAt: string
  endAt: string
  allDay: boolean
  location?: string
}

const timeFormatter = new Intl.DateTimeFormat('fr-FR', {
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const dateTimeFormatter = new Intl.DateTimeFormat('fr-FR', {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const dateFormatter = new Intl.DateTimeFormat('fr-FR', {
  day: 'numeric',
  month: 'short',
})

interface EventListWidgetProps {
  events: DashboardEvent[]
  loading: boolean
  showDate?: boolean
}

export const EventListWidget = ({ events, loading, showDate = false }: EventListWidgetProps) => {
  const { addedIds, removingItems } = useItemTransitions(
    events,
    useCallback((e: DashboardEvent) => e.id, []),
  )
  const removingIds = useMemo(() => new Set(removingItems.map((e) => e.id)), [removingItems])
  const displayEvents = useMemo(() => {
    const currentIds = new Set(events.map((e) => e.id))
    return [...events, ...removingItems.filter((e) => !currentIds.has(e.id))]
  }, [events, removingItems])

  if (loading) {
    return (
      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" gutterBottom>
            Événements
          </Typography>
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} height={32} />
          ))}
        </CardContent>
      </Card>
    )
  }

  return (
    <Card variant="outlined">
      <CardContent sx={{ '&:last-child': { pb: 2 } }}>
        <Typography variant="subtitle2" gutterBottom>
          Événements
          {events.length > 0 && (
            <Typography
              component="span"
              variant="caption"
              color="text.secondary"
              sx={{ ml: 1 }}
            >
              ({events.length})
            </Typography>
          )}
        </Typography>
        {events.length === 0 && removingItems.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Aucun événement
          </Typography>
        ) : (
          <List dense disablePadding>
            {displayEvents.map((event) => (
              <ListItem key={event.id} disableGutters sx={{ py: 0.25, borderRadius: 1, ...transitionSx(event.id, addedIds, removingIds) }}>
                <ListItemText
                  primary={
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <Typography
                        variant="caption"
                        color="text.secondary"
                        sx={{ minWidth: showDate ? 80 : 48, fontVariantNumeric: 'tabular-nums' }}
                      >
                        {event.allDay
                          ? (showDate ? dateFormatter.format(new Date(event.startAt)) : 'Journée')
                          : (showDate ? dateTimeFormatter : timeFormatter).format(new Date(event.startAt))}
                      </Typography>
                      <Typography variant="body2" noWrap>
                        {event.summary}
                      </Typography>
                    </Box>
                  }
                  secondary={
                    event.location ? (
                      <Box
                        sx={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 0.5,
                          ml: '56px',
                        }}
                      >
                        <LocationOnOutlinedIcon sx={{ fontSize: 14 }} />
                        <Typography variant="caption" noWrap>
                          {event.location}
                        </Typography>
                      </Box>
                    ) : undefined
                  }
                />
              </ListItem>
            ))}
          </List>
        )}
      </CardContent>
    </Card>
  )
}
