import { useState, useEffect, useCallback } from 'react'
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

const MERCURE_URL =
  import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'

interface Notification {
  id: string
  type: string
  title: string
  body: string | null
  relatedEntityIri: string | null
  readAt: string | null
  createdAt: string
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
  const { data: notifications = [], refetch } = useGetList<Notification>('notifications', {
    pagination: { page: 1, perPage: 20 },
    sort: { field: 'createdAt', order: 'DESC' },
  })

  // Mercure subscription for real-time updates
  useEffect(() => {
    const url = new URL(MERCURE_URL)
    url.searchParams.append('topic', '/api/notifications/{id}')
    const es = new EventSource(url.toString())
    es.onmessage = () => refetch()
    return () => es.close()
  }, [refetch])

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
          await dataProvider.update('notifications', {
            id: notification.id,
            data: { readAt: new Date().toISOString() },
            previousData: notification,
          })
          refetch()
        } catch {
          // Ignore
        }
      }

      if (notification.relatedEntityIri) {
        setAnchorEl(null)
        redirect(notification.relatedEntityIri)
      }
    },
    [dataProvider, redirect, refetch],
  )

  const handleMarkAllRead = useCallback(async () => {
    const unread = notifications.filter((n) => !n.readAt)
    for (const n of unread) {
      try {
        await dataProvider.update('notifications', {
          id: n.id,
          data: { readAt: new Date().toISOString() },
          previousData: n,
        })
      } catch {
        // Ignore
      }
    }
    refetch()
  }, [dataProvider, notifications, refetch])

  const open = Boolean(anchorEl)

  return (
    <>
      <IconButton color="inherit" onClick={handleClick}>
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
