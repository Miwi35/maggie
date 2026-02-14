import { useCallback, useEffect, useState } from 'react'
import { useDataProvider, useGetIdentity, useNotify } from 'react-admin'
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
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import AddCircleOutlineIcon from '@mui/icons-material/AddCircleOutline'
import LinkIcon from '@mui/icons-material/Link'
import LinkOffIcon from '@mui/icons-material/LinkOff'

const apiUrl = import.meta.env.VITE_API_URL || 'http://localhost/api'

interface GoogleCalendar {
  id: string
  summary: string
  description?: string
  primary: boolean
  backgroundColor?: string
}

interface GoogleTaskList {
  id: string
  title: string
}

interface Agenda {
  iri: string
  ulidId: string
  name: string
  color: string
  googleCalendarId?: string
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
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [agendas, setAgendas] = useState<Agenda[]>([])
  const [googleCalendars, setGoogleCalendars] = useState<GoogleCalendar[]>([])
  const [googleTaskLists, setGoogleTaskLists] = useState<GoogleTaskList[]>([])
  const [connectedTaskListId, setConnectedTaskListId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [connectingAgenda, setConnectingAgenda] = useState<string | null>(null)
  const [importingCalendarId, setImportingCalendarId] = useState<string | null>(null)
  const [connectingTasks, setConnectingTasks] = useState(false)

  const loadData = useCallback(async () => {
    if (!identity) return
    setLoading(true)
    try {
      // Fetch agendas
      const agendaResult = await dataProvider.getList('agendas', {
        pagination: { page: 1, perPage: 100 },
        sort: { field: 'name', order: 'ASC' },
        filter: {},
      })
      setAgendas(
        agendaResult.data.map((a: Record<string, unknown>) => {
          const iri = (a['@id'] || a.id) as string
          return {
            iri,
            ulidId: iri.split('/').pop() as string,
            name: a.name as string,
            color: (a.color || '#1976d2') as string,
            googleCalendarId: a.googleCalendarId as string | undefined,
          }
        }),
      )

      // Fetch Google calendars
      const calRes = await authFetch('/calendar/google/calendars')
      if (calRes.ok) {
        setGoogleCalendars(await calRes.json())
      }

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
  }, [identity, dataProvider, notify])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleConnectAgenda = async (agendaId: string, googleCalendarId: string) => {
    setConnectingAgenda(agendaId)
    try {
      const res = await authFetch('/calendar/google/connect', {
        method: 'POST',
        body: JSON.stringify({ agendaId, googleCalendarId }),
      })
      if (res.ok) {
        notify('Agenda connecté à Google Calendar', { type: 'success' })
        await loadData()
      } else {
        const data = await res.json()
        notify(data.error || 'Erreur de connexion', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setConnectingAgenda(null)
    }
  }

  const handleDisconnectAgenda = async (agendaId: string) => {
    setConnectingAgenda(agendaId)
    try {
      const res = await authFetch('/calendar/google/disconnect', {
        method: 'POST',
        body: JSON.stringify({ agendaId }),
      })
      if (res.ok) {
        notify('Agenda déconnecté de Google Calendar', { type: 'success' })
        await loadData()
      } else {
        const data = await res.json()
        notify(data.error || 'Erreur', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setConnectingAgenda(null)
    }
  }

  const handleImport = async (googleCalendarId: string) => {
    setImportingCalendarId(googleCalendarId)
    try {
      const res = await authFetch('/calendar/google/import', {
        method: 'POST',
        body: JSON.stringify({ googleCalendarId }),
      })
      if (res.ok) {
        notify('Calendrier importé avec succès', { type: 'success' })
        await loadData()
      } else {
        const data = await res.json()
        notify(data.error || "Erreur lors de l'import", { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setImportingCalendarId(null)
    }
  }

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

      {/* Section 0: Import Google Calendars */}
      {(() => {
        const connectedGoogleIds = new Set(
          agendas.filter((a) => a.googleCalendarId).map((a) => a.googleCalendarId),
        )
        const unconnected = googleCalendars.filter((c) => !connectedGoogleIds.has(c.id))
        if (unconnected.length === 0) return null
        return (
          <Card sx={{ mb: 3 }}>
            <CardHeader title="Importer un calendrier Google" />
            <CardContent>
              <Typography variant="body2" color="text.secondary" mb={2}>
                Importez un calendrier Google pour créer automatiquement un agenda Maggie synchronisé.
              </Typography>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Calendrier Google</TableCell>
                    <TableCell align="right">Action</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {unconnected.map((cal) => (
                    <TableRow key={cal.id}>
                      <TableCell>
                        <Stack direction="row" alignItems="center" spacing={1}>
                          {cal.backgroundColor && (
                            <Box
                              sx={{
                                width: 12,
                                height: 12,
                                borderRadius: '50%',
                                bgcolor: cal.backgroundColor,
                                flexShrink: 0,
                              }}
                            />
                          )}
                          <Typography variant="body2">
                            {cal.summary}
                            {cal.primary && ' (principal)'}
                          </Typography>
                        </Stack>
                      </TableCell>
                      <TableCell align="right">
                        <Button
                          size="small"
                          startIcon={
                            importingCalendarId === cal.id ? (
                              <CircularProgress size={16} />
                            ) : (
                              <AddCircleOutlineIcon />
                            )
                          }
                          onClick={() => handleImport(cal.id)}
                          disabled={importingCalendarId !== null}
                        >
                          Importer
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        )
      })()}

      {/* Section 1: Calendar Linking */}
      <Card sx={{ mb: 3 }}>
        <CardHeader title="Calendriers" />
        <CardContent>
          <Typography variant="body2" color="text.secondary" mb={2}>
            Associez vos agendas Maggie à un calendrier Google pour la synchronisation
            bidirectionnelle.
          </Typography>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Agenda Maggie</TableCell>
                <TableCell>Calendrier Google</TableCell>
                <TableCell align="right">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {agendas.map((agenda) => (
                <AgendaRow
                  key={agenda.ulidId}
                  agenda={agenda}
                  googleCalendars={googleCalendars}
                  loading={connectingAgenda === agenda.ulidId}
                  onConnect={handleConnectAgenda}
                  onDisconnect={handleDisconnectAgenda}
                />
              ))}
              {agendas.length === 0 && (
                <TableRow>
                  <TableCell colSpan={3}>
                    <Typography variant="body2" color="text.secondary" textAlign="center">
                      Aucun agenda trouvé. Créez un agenda depuis le calendrier.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Section 2: Tasks */}
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

function AgendaRow({
  agenda,
  googleCalendars,
  loading,
  onConnect,
  onDisconnect,
}: {
  agenda: Agenda
  googleCalendars: GoogleCalendar[]
  loading: boolean
  onConnect: (agendaId: string, googleCalendarId: string) => void
  onDisconnect: (agendaId: string) => void
}) {
  const [selectedCalId, setSelectedCalId] = useState('')

  if (agenda.googleCalendarId) {
    const linked = googleCalendars.find((c) => c.id === agenda.googleCalendarId)
    return (
      <TableRow>
        <TableCell>
          <Stack direction="row" alignItems="center" spacing={1}>
            <Box
              sx={{
                width: 12,
                height: 12,
                borderRadius: '50%',
                bgcolor: agenda.color,
                flexShrink: 0,
              }}
            />
            <Typography variant="body2">{agenda.name}</Typography>
          </Stack>
        </TableCell>
        <TableCell>
          <Chip
            label={linked?.summary || agenda.googleCalendarId}
            size="small"
            color="success"
            variant="outlined"
          />
        </TableCell>
        <TableCell align="right">
          <Button
            size="small"
            color="error"
            startIcon={loading ? <CircularProgress size={16} /> : <LinkOffIcon />}
            onClick={() => onDisconnect(agenda.ulidId)}
            disabled={loading}
          >
            Déconnecter
          </Button>
        </TableCell>
      </TableRow>
    )
  }

  return (
    <TableRow>
      <TableCell>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Box
            sx={{
              width: 12,
              height: 12,
              borderRadius: '50%',
              bgcolor: agenda.color,
              flexShrink: 0,
            }}
          />
          <Typography variant="body2">{agenda.name}</Typography>
        </Stack>
      </TableCell>
      <TableCell>
        <FormControl size="small" sx={{ minWidth: 200 }}>
          <InputLabel>Calendrier Google</InputLabel>
          <Select
            value={selectedCalId}
            label="Calendrier Google"
            onChange={(e) => setSelectedCalId(e.target.value)}
          >
            {googleCalendars.map((cal) => (
              <MenuItem key={cal.id} value={cal.id}>
                {cal.summary}
                {cal.primary && ' (principal)'}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
      </TableCell>
      <TableCell align="right">
        <Button
          size="small"
          startIcon={loading ? <CircularProgress size={16} /> : <LinkIcon />}
          onClick={() => onConnect(agenda.ulidId, selectedCalId)}
          disabled={loading || !selectedCalId}
        >
          Connecter
        </Button>
      </TableCell>
    </TableRow>
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
