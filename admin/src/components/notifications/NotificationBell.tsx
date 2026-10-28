import { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import { useGetList, useDataProvider, useRedirect } from 'react-admin'
import IconButton from '@mui/material/IconButton'
import Badge from '@mui/material/Badge'
import Popover from '@mui/material/Popover'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogContentText from '@mui/material/DialogContentText'
import DialogTitle from '@mui/material/DialogTitle'
import NotificationsIcon from '@mui/icons-material/Notifications'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { useMercure } from '../../hooks/useMercure'
import { getToken } from '../../auth/session'
import { notificationLink, shortageAlertId } from '../maggie/interruptions'
import { fetchNavigationUrl, isTripIri, openNavigation } from '../../modules/calendar/trips'
import { stripDeliveryTags } from '../chat/deliveryTags'

const NOTIFICATION_TOPICS = ['/api/notifications/{id}']

interface Notification {
  id: string
  type: string
  title: string
  body: string | null
  relatedEntityIri: string | null
  readAt: string | null
  createdAt: string
  /** A task reminder's (MAG-510): what its buttons answer, `[]` once answered. */
  reminderId?: string | null
  actions?: string[]
}

/** The answers a task reminder offers, in the words of the push on the phone (MAG-510). */
const ACTION_LABELS: Record<string, string> = {
  start: "Je m'y mets",
  in10: 'Dans 10 min',
  tonight: 'Ce soir',
  done: 'Fait',
  tomorrow: 'Demain',
  open: 'Voir',
  add: 'Ajouter à la liste',
}

const ANSWER_ERROR = 'Impossible de répondre au rappel.'

/** `POST /api/task-reminders/{id}/action` or `/api/shortage-alerts/{id}/action`: the notification as it stands after the answer, if it stays. */
interface ActionResult {
  notification: { title: string; body: string | null; actions: string[] } | null
}

/** What Mercure last told us about each notification, by IRI; `null` is a deletion. */
type Overlay = Record<string, Notification | null>

const PER_PAGE = 20

/** Where a notification's buttons answer: its task reminder, or the missing product it announces (MAG-570). */
function actionUrl(notification: Notification): string | null {
  if (notification.reminderId) return `/api/task-reminders/${encodeURIComponent(notification.reminderId)}/action`
  const shortageId = shortageAlertId(notification.relatedEntityIri)
  return shortageId ? `/api/shortage-alerts/${shortageId}/action` : null
}

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
  const [confirmingClear, setConfirmingClear] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [answering, setAnswering] = useState<string | null>(null)
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
    setError(null)
  }

  // « Pars maintenant » (MAG-655) opens the trip's navigation link. It is read when the bell
  // opens, so that the click opens the tab at once: a tab opened after a wait is blocked.
  const [navigation, setNavigation] = useState<Record<string, string>>({})
  const departureTrips = useMemo(
    () => notifications.flatMap((n) => (n.type === 'departure' && isTripIri(n.relatedEntityIri) ? [n.relatedEntityIri] : [])),
    [notifications],
  )
  const departureKey = departureTrips.join(',')
  const bellOpen = Boolean(anchorEl)
  useEffect(() => {
    if (!bellOpen || !departureKey) return
    let live = true
    for (const iri of departureKey.split(',')) {
      void fetchNavigationUrl(iri).then((url) => {
        if (live && url) setNavigation((known) => (known[iri] === url ? known : { ...known, [iri]: url }))
      })
    }

    return () => {
      live = false
    }
  }, [bellOpen, departureKey])

  const handleNotificationClick = useCallback(
    async (notification: Notification) => {
      const tripIri = notification.type === 'departure' && isTripIri(notification.relatedEntityIri) ? notification.relatedEntityIri : null
      const knownUrl = tripIri ? navigation[tripIri] : undefined
      if (knownUrl) openNavigation(knownUrl)

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

      if (tripIri && !knownUrl) {
        const url = await fetchNavigationUrl(tripIri)
        if (url) openNavigation(url)
      }
      if (tripIri) setAnchorEl(null)

      const link = notificationLink(notification.relatedEntityIri)
      if (link) {
        setAnchorEl(null)
        redirect(link.path)
      }
    },
    [apply, dataProvider, navigation, redirect],
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

  const handleDelete = useCallback(
    async (notification: Notification) => {
      setError(null)
      try {
        await dataProvider.delete('notifications', { id: notification.id, previousData: notification })
        apply({ '@id': notification.id, deleted: true })
      } catch {
        setError('Impossible de supprimer la notification.')
      }
    },
    [apply, dataProvider],
  )

  const handleClear = useCallback(async () => {
    setClearing(true)
    setError(null)
    try {
      const token = getToken()
      const response = await fetch('/api/notifications', {
        method: 'DELETE',
        headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      setConfirmingClear(false)
      for (const n of notifications) apply({ '@id': n.id, deleted: true })
    } catch {
      setConfirmingClear(false)
      setError("Impossible d'effacer les notifications.")
    } finally {
      setClearing(false)
    }
  }, [apply, notifications])

  // « Dans 10 min », « Ce soir » and « Ajouter à la liste » delete the notification (a new
  // one comes back later, or the shortage is settled); the other answers keep it, read,
  // with the words the API gives it.
  const handleAction = useCallback(
    async (notification: Notification, action: string) => {
      const url = actionUrl(notification)
      if (!url) return
      setAnswering(notification.id)
      setError(null)
      try {
        const token = getToken()
        const response = await fetch(url, {
          method: 'POST',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: JSON.stringify({ action }),
        })
        const body = await response.json().catch(() => null)
        if (!response.ok) {
          setError(response.status === 409 && typeof body?.error === 'string' ? body.error : ANSWER_ERROR)
          return
        }
        const kept = (body as ActionResult | null)?.notification
        if (action === 'in10' || action === 'tonight' || !kept) {
          apply({ '@id': notification.id, deleted: true })
        } else {
          apply({
            '@id': notification.id,
            title: kept.title,
            body: kept.body,
            actions: kept.actions,
            readAt: notification.readAt ?? new Date().toISOString(),
          })
        }
        // No Focus screen has a route of its own: « Je m'y mets » opens the task; « Voir » opens the routines of the day.
        const link = action === 'start' || action === 'open' ? notificationLink(notification.relatedEntityIri) : null
        if (link) {
          setAnchorEl(null)
          redirect(link.path)
        }
      } catch {
        setError(ANSWER_ERROR)
      } finally {
        setAnswering(null)
      }
    },
    [apply, redirect],
  )

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
              // On a 360px phone the actions wrap under the title (MAG-387).
              flexWrap: 'wrap',
              gap: 0.5,
            }}
          >
            <Typography variant="subtitle1" fontWeight={600}>
              Notifications
            </Typography>
            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', '& .MuiButton-root': { whiteSpace: 'nowrap' } }}>
              {unreadCount > 0 && (
                <Button size="small" onClick={handleMarkAllRead}>
                  Tout marquer comme lu
                </Button>
              )}
              {notifications.length > 0 && (
                <Button size="small" color="error" onClick={() => setConfirmingClear(true)}>
                  Tout effacer
                </Button>
              )}
            </Box>
          </Box>
          <Divider />

          {error && (
            <Typography role="alert" variant="body2" color="error" sx={{ px: 2, py: 1 }}>
              {error}
            </Typography>
          )}

          {notifications.length === 0 ? (
            <Box sx={{ p: 3, textAlign: 'center' }}>
              <Typography variant="body2" color="text.secondary">
                Aucune notification
              </Typography>
            </Box>
          ) : (
            <List sx={{ overflowY: 'auto', flex: 1, py: 0 }}>
              {notifications.map((n) => (
                <ListItem
                  key={n.id}
                  disablePadding
                  sx={{ flexDirection: 'column', alignItems: 'stretch' }}
                  secondaryAction={
                    <IconButton edge="end" size="small" aria-label="Supprimer" onClick={() => handleDelete(n)}>
                      <DeleteOutlineIcon fontSize="small" />
                    </IconButton>
                  }
                >
                  <ListItemButton
                    onClick={() => handleNotificationClick(n)}
                    sx={{
                      pr: 7,
                      bgcolor: n.readAt ? 'transparent' : 'action.hover',
                      borderLeft: n.readAt ? 'none' : '3px solid',
                      borderLeftColor: 'primary.main',
                    }}
                  >
                    <ListItemText
                      primary={n.title}
                      secondary={
                        n.body ? (
                          <>
                            <Box component="span" sx={{ display: 'block', color: 'text.primary', fontSize: 13 }}>
                              {stripDeliveryTags(n.body)}
                            </Box>
                            {timeAgo(n.createdAt)}
                          </>
                        ) : (
                          timeAgo(n.createdAt)
                        )
                      }
                      primaryTypographyProps={{
                        fontWeight: n.readAt ? 400 : 600,
                        fontSize: 14,
                      }}
                      secondaryTypographyProps={{ fontSize: 12 }}
                    />
                  </ListItemButton>
                  {n.actions && n.actions.length > 0 && actionUrl(n) && (
                    <Box
                      sx={{
                        display: 'flex',
                        flexWrap: 'wrap',
                        gap: 0.5,
                        px: 2,
                        pr: 7,
                        pb: 1,
                        bgcolor: n.readAt ? 'transparent' : 'action.hover',
                        borderLeft: n.readAt ? 'none' : '3px solid',
                        borderLeftColor: 'primary.main',
                      }}
                    >
                      {n.actions.map((action) => (
                        <Button
                          key={action}
                          size="small"
                          variant={action === 'start' || action === 'add' ? 'contained' : 'outlined'}
                          disabled={answering === n.id}
                          onClick={(e) => {
                            e.stopPropagation()
                            handleAction(n, action)
                          }}
                        >
                          {ACTION_LABELS[action] ?? action}
                        </Button>
                      ))}
                    </Box>
                  )}
                </ListItem>
              ))}
            </List>
          )}
        </Box>
      </Popover>

      <Dialog open={confirmingClear} onClose={() => !clearing && setConfirmingClear(false)}>
        <DialogTitle>Effacer toutes les notifications ?</DialogTitle>
        <DialogContent>
          <DialogContentText>Cette action est définitive.</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmingClear(false)} disabled={clearing}>
            Annuler
          </Button>
          <Button color="error" onClick={handleClear} disabled={clearing}>
            Effacer
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
