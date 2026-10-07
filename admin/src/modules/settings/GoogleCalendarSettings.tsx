import { useCallback, useEffect, useState } from 'react'
import { useGetIdentity, useNotify } from 'react-admin'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CardHeader from '@mui/material/CardHeader'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import LinkIcon from '@mui/icons-material/Link'
import LinkOffIcon from '@mui/icons-material/LinkOff'

const apiUrl = import.meta.env.VITE_API_URL || 'http://localhost/api'

interface GoogleTaskList {
  id: string
  title: string
}

function authFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')
  return fetch(`${apiUrl}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}

/**
 * Which Google Tasks list Maggie syncs with (MAG-118).
 *
 * The three routes this screen calls did not exist until MAG-118, so the choice
 * it offered went nowhere and the API synced whichever list Google returned
 * first. They exist now, and the screen shows the choice as the account makes
 * it: one list is stated, several are offered.
 */
export const GoogleCalendarSettings = () => {
  const { identity } = useGetIdentity()
  const notify = useNotify()

  const [googleTaskLists, setGoogleTaskLists] = useState<GoogleTaskList[]>([])
  const [connectedTaskListId, setConnectedTaskListId] = useState<string | null>(null)
  const [googleAuthorized, setGoogleAuthorized] = useState(true)
  // Whether the lists on screen are Google's answer. An empty list and a failed
  // fetch look the same otherwise, and the screen would then tell the owner
  // their list no longer exists because the API was briefly down.
  const [taskListsLoaded, setTaskListsLoaded] = useState(false)
  const [loading, setLoading] = useState(true)
  const [connectingTasks, setConnectingTasks] = useState(false)

  const loadData = useCallback(async () => {
    if (!identity) return
    setLoading(true)
    try {
      const tlRes = await authFetch('/calendar/google/task-lists')
      if (tlRes.status === 403) {
        // The account was never authorized, or the authorization was revoked.
        setGoogleAuthorized(false)
        setGoogleTaskLists([])
        setTaskListsLoaded(false)
      } else if (tlRes.ok) {
        setGoogleAuthorized(true)
        setGoogleTaskLists((await tlRes.json()) as GoogleTaskList[])
        setTaskListsLoaded(true)
      } else {
        setTaskListsLoaded(false)
        notify('Erreur lors du chargement des listes Google Tasks', { type: 'error' })
      }

      const meRes = await authFetch('/users/me')
      if (meRes.ok) {
        const meData = (await meRes.json()) as { googleTaskListId?: string | null }
        setConnectedTaskListId(meData.googleTaskListId ?? null)
      }
    } catch {
      notify('Erreur lors du chargement des paramètres', { type: 'error' })
    } finally {
      setLoading(false)
    }
  }, [identity, notify])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleConnectTasks = async (googleTaskListId: string) => {
    setConnectingTasks(true)
    try {
      const res = await authFetch('/calendar/google/connect-tasks', {
        method: 'POST',
        body: JSON.stringify({ googleTaskListId }),
      })
      if (res.ok) {
        notify('Tâches synchronisées avec Google Tasks', { type: 'success' })
        setConnectedTaskListId(googleTaskListId)
      } else {
        const data = (await res.json()) as { error?: string }
        notify(data.error || 'Erreur', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setConnectingTasks(false)
    }
  }

  const handleDisconnectTasks = async () => {
    setConnectingTasks(true)
    try {
      const res = await authFetch('/calendar/google/disconnect-tasks', {
        method: 'POST',
      })
      if (res.ok) {
        notify('Tâches déconnectées de Google Tasks', { type: 'success' })
        setConnectedTaskListId(null)
      } else {
        const data = (await res.json()) as { error?: string }
        notify(data.error || 'Erreur', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setConnectingTasks(false)
    }
  }

  if (loading) {
    return (
      <Box display="flex" justifyContent="center" mt={4}>
        <CircularProgress />
      </Box>
    )
  }

  const connectedTaskList = googleTaskLists.find((tl) => tl.id === connectedTaskListId)
  // Chosen, and gone from the account since: Google no longer holds the list,
  // or stopped sharing it. The sync drops the choice at its next run, but the
  // owner is here now and can pick again straight away.
  //
  // Only ever said when Google really answered: a failed fetch leaves no list
  // either, and claiming the owner's list is gone because the API hiccuped is
  // a statement about their Google account that nothing checked.
  const connectedTaskListMissing =
    taskListsLoaded && null !== connectedTaskListId && undefined === connectedTaskList

  return (
    <Box maxWidth={800} mx="auto" mt={2}>
      <Typography variant="h5" mb={3}>
        Paramètres Google
      </Typography>

      <Card sx={{ mb: 3 }}>
        <CardHeader title="Tâches" />
        <CardContent>
          <Typography variant="body2" color="text.secondary" mb={2}>
            Synchronisez vos tâches Maggie avec une liste Google Tasks.
          </Typography>
          <Divider sx={{ mb: 2 }} />
          {!googleAuthorized ? (
            <Alert severity="info">
              Connectez votre compte Google pour synchroniser vos tâches.
            </Alert>
          ) : !taskListsLoaded && !connectedTaskListId ? (
            // Google's lists never arrived, and there is no stored choice to
            // fall back on: say so rather than "aucune liste sur ce compte",
            // which is the one thing this screen cannot know right now.
            <Alert severity="warning">
              Les listes Google Tasks n’ont pas pu être chargées. Rechargez la page.
            </Alert>
          ) : (
            <Stack spacing={2}>
              {connectedTaskListMissing && (
                <Alert severity="warning">
                  La liste synchronisée n’existe plus sur Google. Choisissez-en une autre.
                </Alert>
              )}
              {connectedTaskListId && !connectedTaskListMissing ? (
                <Stack direction="row" alignItems="center" spacing={2}>
                  <Chip label="Synchronisée" color="success" size="small" />
                  <Typography variant="body2">
                    {/* The stored identifier when the title is unknown — the
                        lists did not load, and showing an empty pair of quotes
                        would say less than Google's own id. */}
                    Synchronisée avec «&nbsp;{connectedTaskList?.title ?? connectedTaskListId}&nbsp;»
                  </Typography>
                  <Button
                    size="small"
                    color="error"
                    startIcon={connectingTasks ? <CircularProgress size={16} /> : <LinkOffIcon />}
                    onClick={handleDisconnectTasks}
                    disabled={connectingTasks}
                  >
                    Déconnecter
                  </Button>
                </Stack>
              ) : (
                <TaskListConnect
                  taskLists={googleTaskLists}
                  loading={connectingTasks}
                  onConnect={handleConnectTasks}
                />
              )}
            </Stack>
          )}
        </CardContent>
      </Card>
    </Box>
  )
}

/**
 * Offers the lists left to choose from — a menu only when there is a choice.
 *
 * A Google account usually holds one list, and a select with a single option is
 * a question with one answer. The owner asked for the single list to be stated
 * rather than offered (MAG-118).
 */
function TaskListConnect({
  taskLists,
  loading,
  onConnect,
}: {
  taskLists: GoogleTaskList[]
  loading: boolean
  onConnect: (googleTaskListId: string) => void
}) {
  const onlyTaskList = taskLists.length === 1 ? taskLists[0] : undefined
  const [selected, setSelected] = useState('')
  const chosen = onlyTaskList?.id ?? selected

  if (taskLists.length === 0) {
    return <Alert severity="info">Aucune liste Google Tasks sur ce compte.</Alert>
  }

  return (
    <Stack direction="row" alignItems="center" spacing={2}>
      {onlyTaskList ? (
        <Typography variant="body2">Liste Google Tasks : «&nbsp;{onlyTaskList.title}&nbsp;»</Typography>
      ) : (
        <FormControl size="small" sx={{ minWidth: 200 }}>
          <InputLabel id="google-task-list-label">Liste Google Tasks</InputLabel>
          <Select
            labelId="google-task-list-label"
            value={selected}
            label="Liste Google Tasks"
            onChange={(e) => setSelected(e.target.value)}
          >
            {taskLists.map((tl) => (
              <MenuItem key={tl.id} value={tl.id}>
                {tl.title}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
      )}
      <Button
        size="small"
        startIcon={loading ? <CircularProgress size={16} /> : <LinkIcon />}
        onClick={() => onConnect(chosen)}
        disabled={loading || !chosen}
      >
        Connecter
      </Button>
    </Stack>
  )
}
