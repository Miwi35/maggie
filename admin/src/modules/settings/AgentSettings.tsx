import { useCallback, useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CardHeader from '@mui/material/CardHeader'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import SaveIcon from '@mui/icons-material/Save'

const MERCURE_URL =
  import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'

interface PersonalityConfig {
  name: string
  language: string
  backstory: string
}

interface Proaction {
  id: string
  prompt: string
  status: string | null
  scheduledAt: string | null
  createdAt: string | null
}

const statusColors: Record<string, 'default' | 'warning' | 'success' | 'error' | 'info'> = {
  pending: 'warning',
  running: 'info',
  completed: 'success',
  failed: 'error',
}

function agentFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')
  return fetch(`/agent${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('fr-FR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export const AgentSettings = () => {
  const notify = useNotify()
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [personality, setPersonality] = useState<PersonalityConfig>({
    name: '',
    language: '',
    backstory: '',
  })
  const [proactions, setProactions] = useState<Proaction[]>([])

  const loadData = useCallback(async () => {
    setLoading(true)
    try {
      const [persRes, proRes] = await Promise.all([
        agentFetch('/personality'),
        agentFetch('/proactions'),
      ])
      if (persRes.ok) setPersonality(await persRes.json())
      if (proRes.ok) setProactions(await proRes.json())
    } catch {
      notify('Erreur lors du chargement des paramètres agent', { type: 'error' })
    } finally {
      setLoading(false)
    }
  }, [notify])

  useEffect(() => {
    loadData()
  }, [loadData])

  // Mercure subscription for live proaction updates
  useEffect(() => {
    const userStr = localStorage.getItem('user')
    const userId = userStr ? JSON.parse(userStr).id : null
    if (!userId) return

    const url = new URL(MERCURE_URL)
    url.searchParams.append('topic', `/proactions/${userId}`)
    const eventSource = new EventSource(url.toString())

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        setProactions((prev) => {
          const idx = prev.findIndex((p) => p.id === data.id)
          if (idx >= 0) {
            const next = [...prev]
            next[idx] = data
            return next
          }
          return [data, ...prev]
        })
      } catch {
        // ignore malformed messages
      }
    }

    return () => eventSource.close()
  }, [])

  const handleSave = async () => {
    setSaving(true)
    try {
      const res = await agentFetch('/personality', {
        method: 'PUT',
        body: JSON.stringify(personality),
      })
      if (res.ok) {
        setPersonality(await res.json())
        notify('Personnalité mise à jour', { type: 'success' })
      } else {
        notify('Erreur lors de la sauvegarde', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setSaving(false)
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
    <Box maxWidth={900} mx="auto" mt={2}>
      <Typography variant="h5" mb={3}>
        Paramètres de l'agent
      </Typography>

      {/* Personality card */}
      <Card sx={{ mb: 3 }}>
        <CardHeader title="Personnalité de l'agent" />
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" spacing={2}>
              <TextField
                label="Nom"
                value={personality.name}
                onChange={(e) => setPersonality((p) => ({ ...p, name: e.target.value }))}
                size="small"
                sx={{ flex: 1 }}
              />
              <TextField
                label="Langue"
                value={personality.language}
                onChange={(e) => setPersonality((p) => ({ ...p, language: e.target.value }))}
                size="small"
                sx={{ flex: 1 }}
              />
            </Stack>
            <TextField
              label="Backstory"
              value={personality.backstory}
              onChange={(e) => setPersonality((p) => ({ ...p, backstory: e.target.value }))}
              multiline
              minRows={3}
              maxRows={10}
              fullWidth
              helperText="Décrivez la personnalité et le comportement de l'agent"
            />
            <Divider />
            <Box display="flex" justifyContent="flex-end">
              <Button
                variant="contained"
                startIcon={saving ? <CircularProgress size={16} /> : <SaveIcon />}
                onClick={handleSave}
                disabled={saving}
              >
                Enregistrer
              </Button>
            </Box>
          </Stack>
        </CardContent>
      </Card>

      {/* Proactions card */}
      <Card>
        <CardHeader title="Proactions planifiées" />
        <CardContent>
          {proactions.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Aucune proaction pour le moment.
            </Typography>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Prompt</TableCell>
                    <TableCell>Statut</TableCell>
                    <TableCell>Planifiée</TableCell>
                    <TableCell>Créée</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {proactions.map((p) => (
                    <TableRow key={p.id}>
                      <TableCell sx={{ maxWidth: 350 }}>
                        <Typography variant="body2" noWrap title={p.prompt}>
                          {p.prompt}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={p.status ?? 'unknown'}
                          color={statusColors[p.status ?? ''] ?? 'default'}
                          size="small"
                        />
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">{formatDateTime(p.scheduledAt)}</Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">{formatDateTime(p.createdAt)}</Typography>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>
    </Box>
  )
}
