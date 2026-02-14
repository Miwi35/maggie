import { useCallback, useEffect, useState } from 'react'
import { useGetIdentity, useNotify } from 'react-admin'
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

export const GoogleCalendarSettings = () => {
  const { identity } = useGetIdentity()
  const notify = useNotify()

  const [googleTaskLists, setGoogleTaskLists] = useState<GoogleTaskList[]>([])
  const [connectedTaskListId, setConnectedTaskListId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [connectingTasks, setConnectingTasks] = useState(false)

  const loadData = useCallback(async () => {
    if (!identity) return
    setLoading(true)
    try {
      // Fetch Google Task Lists
      const tlRes = await authFetch('/calendar/google/task-lists')
      if (tlRes.ok) {
        setGoogleTaskLists(await tlRes.json())
      }

      // Check user's connected task list
      const meRes = await authFetch('/users/me')
      if (meRes.ok) {
        const meData = await meRes.json()
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
        notify('Tâches connectées à Google Tasks', { type: 'success' })
        setConnectedTaskListId(googleTaskListId)
      } else {
        const data = await res.json()
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
        const data = await res.json()
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
          {connectedTaskListId ? (
            <Stack direction="row" alignItems="center" spacing={2}>
              <Chip label="Connecté" color="success" size="small" />
              <Typography variant="body2">
                {googleTaskLists.find((tl) => tl.id === connectedTaskListId)?.title ||
                  connectedTaskListId}
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
        </CardContent>
      </Card>
    </Box>
  )
}

function TaskListConnect({
  taskLists,
  loading,
  onConnect,
}: {
  taskLists: GoogleTaskList[]
  loading: boolean
  onConnect: (googleTaskListId: string) => void
}) {
  const [selected, setSelected] = useState('')

  return (
    <Stack direction="row" alignItems="center" spacing={2}>
      <FormControl size="small" sx={{ minWidth: 200 }}>
        <InputLabel>Liste Google Tasks</InputLabel>
        <Select
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
      <Button
        size="small"
        startIcon={loading ? <CircularProgress size={16} /> : <LinkIcon />}
        onClick={() => onConnect(selected)}
        disabled={loading || !selected}
      >
        Connecter
      </Button>
    </Stack>
  )
}
