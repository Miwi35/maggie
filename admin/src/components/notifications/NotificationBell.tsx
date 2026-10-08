import { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import { useGetList, useDataProvider, useRedirect } from 'react-admin'
import IconButton from '@mui/material/IconButton'
import Badge from '@mui/material/Badge'
import Popover from '@mui/material/Popover'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import NotificationsIcon from '@mui/icons-material/Notifications'
import { useMercure } from '../../hooks/useMercure'

const NOTIFICATION_TOPICS = ['/api/notifications/{id}']

interface Notification {
  id: string
  type: string
  title: string
  body: string | null
  relatedEntityIri: string | null
  readAt: string | null
  createdAt: string
}

/** What Mercure last told us about each notification, by IRI; `null` is a deletion. */
type Overlay = Record<string, Notification | null>

const PER_PAGE = 20

/** The IRI: the `id` react-admin's Hydra provider gives a record, and the `@id` of a Mercure payload. */
function iriOf(payload: Record<string, unknown>): string | null {
  if (typeof payload['@id'] === 'string') return payload['@id']
  return typeof payload.id === 'string' && payload.id.startsWith('/') ? payload.id : null
}

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime()
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return "à l'instant"
  if (minutes < 60) return `il y a ${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `il y a ${hours}h`
  const days = Math.floor(hours / 24)
  return `il y a ${days}j`
}

export const NotificationBell = () => {
  const [anchorEl, setAnchorEl] = useState<HTMLButtonElement | null>(null)
  const dataProvider = useDataProvider()
  const redirect = useRedirect()

  // Use react-admin hook so 401 triggers checkError → auto logout
  const { data: listed = [], refetch } = useGetList<Notification>('notifications', {
    pagination: { page: 1, perPage: PER_PAGE },
    sort: { field: 'createdAt', order: 'DESC' },
  })

  // The list is served from Elasticsearch, indexed after the write: a read made
  // when the Mercure event arrives can still miss the change. What the events
  // said wins over the list, which stays the source at load and as a fallback.
  const [overlay, setOverlay] = useState<Overlay>({})
  const overlayRef = useRef(overlay)
  const listedRef = useRef(listed)
  useEffect(() => {
    listedRef.current = listed
  })

  // Returns false when the payload cannot stand alone (a differential update of a
  // notification we have never seen): the caller then falls back to the list.
  const apply = useCallback((payload: Record<string, unknown>): boolean => {
    const iri = iriOf(payload)
    if (!iri) return false

    let next: Notification | null
    if (payload.deleted === true) {
      next = null
    } else {
      const known = iri in overlayRef.current ? overlayRef.current[iri] : listedRef.current.find((n) => n.id === iri)
      const merged = { ...known, ...payload, id: iri } as Partial<Notification>
      if (typeof merged.title !== 'string' || typeof merged.createdAt !== 'string') return false
      next = merged as Notification
    }

    overlayRef.current = { ...overlayRef.current, [iri]: next }
    setOverlay(overlayRef.current)
    return true
  }, [])

  // Mercure subscription for real-time updates
  useMercure(NOTIFICATION_TOPICS, (data) => {
    try {
      const payload = JSON.parse(data ?? '')
      if (payload && typeof payload === 'object' && apply(payload)) return
    } catch {
      // Not an event we can read: let the list say.
    }
    refetch()
  })

  const notifications = useMemo(() => {
    const byId = new Map(listed.map((n) => [n.id, n]))
    for (const [id, n] of Object.entries(overlay)) {
      if (n === null) byId.delete(id)
      else byId.set(id, n)
    }
    return [...byId.values()].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)).slice(0, PER_PAGE)
  }, [listed, overlay])

  const unreadCount = notifications.filter((n) => !n.readAt).length

  const handleClick = (event: React.MouseEvent<HTMLButtonElement>) => {
    setAnchorEl(event.currentTarget)
  }

  const handleClose = () => {
    setAnchorEl(null)
  }

  const handleNotificationClick = useCallback(
    async (notification: Notification) => {
      if (!notification.readAt) {
        try {
          const { data } = await dataProvider.update('notifications', {
            id: notification.id,
            data: { readAt: new Date().toISOString() },
            previousData: notification,
          })
          apply(data)
        } catch {
          // Ignore
        }
      }

      if (notification.relatedEntityIri) {
        setAnchorEl(null)
        redirect(notification.relatedEntityIri)
      }
    },
    [apply, dataProvider, redirect],
  )

  const handleMarkAllRead = useCallback(async () => {
    const unread = notifications.filter((n) => !n.readAt)
    for (const n of unread) {
      try {
        const { data } = await dataProvider.update('notifications', {
          id: n.id,
          data: { readAt: new Date().toISOString() },
          previousData: n,
        })
        apply(data)
      } catch {
        // Ignore
      }
    }
  }, [apply, dataProvider, notifications])

  const open = Boolean(anchorEl)

  return (
    <>
      <IconButton color="inherit" aria-label="Notifications" onClick={handleClick}>
        <Badge badgeContent={unreadCount} color="error">
          <NotificationsIcon />
        </Badge>
      </IconButton>

      <Popover
        open={open}
        anchorEl={anchorEl}
        onClose={handleClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        <Box sx={{ width: 360, maxHeight: 480, display: 'flex', flexDirection: 'column' }}>
          <Box
            sx={{
              px: 2,
              py: 1.5,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
            }}
          >
            <Typography variant="subtitle1" fontWeight={600}>
              Notifications
            </Typography>
            {unreadCount > 0 && (
              <Button size="small" onClick={handleMarkAllRead}>
                Tout marquer comme lu
              </Button>
            )}
          </Box>
          <Divider />

          {notifications.length === 0 ? (
            <Box sx={{ p: 3, textAlign: 'center' }}>
              <Typography variant="body2" color="text.secondary">
                Aucune notification
              </Typography>
            </Box>
          ) : (
            <List sx={{ overflowY: 'auto', flex: 1, py: 0 }}>
              {notifications.map((n) => (
                <ListItemButton
                  key={n.id}
                  onClick={() => handleNotificationClick(n)}
                  sx={{
                    bgcolor: n.readAt ? 'transparent' : 'action.hover',
                    borderLeft: n.readAt ? 'none' : '3px solid',
                    borderLeftColor: 'primary.main',
                  }}
                >
                  <ListItemText
                    primary={n.title}
                    secondary={timeAgo(n.createdAt)}
                    primaryTypographyProps={{
                      fontWeight: n.readAt ? 400 : 600,
                      fontSize: 14,
                    }}
                    secondaryTypographyProps={{ fontSize: 12 }}
                  />
                </ListItemButton>
              ))}
            </List>
          )}
        </Box>
      </Popover>
    </>
  )
}
