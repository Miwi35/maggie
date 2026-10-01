import { useCallback, useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import { AGENT_STREAMS, agentTopic, getStoredUserId } from '../../hooks/agentTopics'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Collapse from '@mui/material/Collapse'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import EditIcon from '@mui/icons-material/Edit'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
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

interface Instruction {
  id: string
  content: string
  createdAt: string | null
  updatedAt: string | null
}

interface SkillSummary {
  name: string
  description: string
  tags: string[]
}

interface SkillForm {
  name: string
  description: string
  tags: string
  content: string
}

const emptySkillForm: SkillForm = { name: '', description: '', tags: '', content: '' }

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

  // Instructions state
  const [instructions, setInstructions] = useState<Instruction[]>([])
  const [newInstructionContent, setNewInstructionContent] = useState('')
  const [addingInstruction, setAddingInstruction] = useState(false)

  // Skills state
  const [skills, setSkills] = useState<SkillSummary[]>([])
  const [expandedSkill, setExpandedSkill] = useState<string | null>(null)
  const [skillContent, setSkillContent] = useState<Record<string, string>>({})
  const [skillForm, setSkillForm] = useState<SkillForm>(emptySkillForm)
  const [addingSkill, setAddingSkill] = useState(false)
  const [editDialog, setEditDialog] = useState<SkillForm | null>(null)
  const [savingSkill, setSavingSkill] = useState(false)
  const [tab, setTab] = useState(0)

  const loadData = useCallback(async () => {
    setLoading(true)
    try {
      const [persRes, proRes, instrRes, skillRes] = await Promise.all([
        agentFetch('/personality'),
        agentFetch('/proactions'),
        agentFetch('/instructions'),
        agentFetch('/skills'),
      ])
      if (persRes.ok) setPersonality(await persRes.json())
      if (proRes.ok) setProactions(await proRes.json())
      if (instrRes.ok) setInstructions(await instrRes.json())
      if (skillRes.ok) setSkills(await skillRes.json())
    } catch {
      notify('Erreur lors du chargement des paramètres agent', { type: 'error' })
    } finally {
      setLoading(false)
    }
  }, [notify])

  useEffect(() => {
    loadData()
  }, [loadData])

  // Mercure subscriptions for live updates
  useEffect(() => {
    const userId = getStoredUserId()
    if (!userId) return

    const url = new URL(MERCURE_URL, window.location.origin)
    url.searchParams.append('topic', agentTopic(AGENT_STREAMS.proactions, userId))
    url.searchParams.append('topic', agentTopic(AGENT_STREAMS.instructions, userId))
    url.searchParams.append('topic', agentTopic(AGENT_STREAMS.skills, userId))
    const eventSource = new EventSource(url.toString(), { withCredentials: true })

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)

        // Detect type by shape
        if ('prompt' in data && 'status' in data) {
          // Proaction
          setProactions((prev) => {
            const idx = prev.findIndex((p) => p.id === data.id)
            if (idx >= 0) {
              const next = [...prev]
              next[idx] = data
              return next
            }
            return [data, ...prev]
          })
        } else if ('tags' in data || (data.deleted && 'name' in data)) {
          // Skill
          if (data.deleted) {
            setSkills((prev) => prev.filter((s) => s.name !== data.name))
          } else {
            setSkills((prev) => {
              const idx = prev.findIndex((s) => s.name === data.name)
              if (idx >= 0) {
                const next = [...prev]
                next[idx] = data
                return next
              }
              return [...prev, data]
            })
          }
        } else if ('content' in data || (data.deleted && 'id' in data)) {
          // Instruction
          if (data.deleted) {
            setInstructions((prev) => prev.filter((i) => i.id !== data.id))
          } else {
            setInstructions((prev) => {
              const idx = prev.findIndex((i) => i.id === data.id)
              if (idx >= 0) {
                const next = [...prev]
                next[idx] = data
                return next
              }
              return [data, ...prev]
            })
          }
        }
      } catch {
        // ignore malformed messages
      }
    }

    return () => eventSource.close()
  }, [])

  // --- Personality handlers ---

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

  // --- Instruction handlers ---

  const handleAddInstruction = async () => {
    if (!newInstructionContent.trim()) return
    setAddingInstruction(true)
    try {
      const res = await agentFetch('/instructions', {
        method: 'POST',
        body: JSON.stringify({ content: newInstructionContent.trim() }),
      })
      if (res.ok) {
        const instruction = await res.json()
        setInstructions((prev) => [instruction, ...prev])
        setNewInstructionContent('')
        notify('Instruction ajoutée', { type: 'success' })
      } else {
        notify("Erreur lors de l'ajout", { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setAddingInstruction(false)
    }
  }

  const handleDeleteInstruction = async (id: string) => {
    try {
      const res = await agentFetch(`/instructions/${id}`, { method: 'DELETE' })
      if (res.ok) {
        setInstructions((prev) => prev.filter((i) => i.id !== id))
        notify('Instruction supprimée', { type: 'success' })
      } else {
        notify('Erreur lors de la suppression', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    }
  }

  // --- Skill handlers ---

  const handleExpandSkill = async (name: string) => {
    if (expandedSkill === name) {
      setExpandedSkill(null)
      return
    }
    setExpandedSkill(name)
    if (!skillContent[name]) {
      try {
        const res = await agentFetch(`/skills/${encodeURIComponent(name)}`)
        if (res.ok) {
          const data = await res.json()
          setSkillContent((prev) => ({ ...prev, [name]: data.content }))
        }
      } catch {
        // ignore
      }
    }
  }

  const handleAddSkill = async () => {
    if (!skillForm.name.trim() || !skillForm.content.trim()) return
    setAddingSkill(true)
    try {
      const tags = skillForm.tags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean)
      const res = await agentFetch('/skills', {
        method: 'POST',
        body: JSON.stringify({
          name: skillForm.name.trim(),
          description: skillForm.description.trim(),
          tags,
          content: skillForm.content.trim(),
        }),
      })
      if (res.ok) {
        const skill = await res.json()
        setSkills((prev) => [...prev, skill])
        setSkillForm(emptySkillForm)
        notify('Compétence créée', { type: 'success' })
      } else {
        notify('Erreur lors de la création', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setAddingSkill(false)
    }
  }

  const handleEditSkill = async (name: string) => {
    try {
      const res = await agentFetch(`/skills/${encodeURIComponent(name)}`)
      if (res.ok) {
        const data = await res.json()
        let body = data.content || ''
        const parts = body.split('---')
        if (parts.length >= 3) {
          body = parts.slice(2).join('---').trim()
        }
        setEditDialog({
          name: data.name,
          description: data.description || '',
          tags: (data.tags || []).join(', '),
          content: body,
        })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    }
  }

  const handleSaveSkillEdit = async () => {
    if (!editDialog) return
    setSavingSkill(true)
    try {
      const tags = editDialog.tags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean)
      const res = await agentFetch(`/skills/${encodeURIComponent(editDialog.name)}`, {
        method: 'PUT',
        body: JSON.stringify({
          description: editDialog.description,
          tags,
          content: editDialog.content,
        }),
      })
      if (res.ok) {
        const updated = await res.json()
        setSkills((prev) => prev.map((s) => (s.name === updated.name ? updated : s)))
        setSkillContent((prev) => {
          const next = { ...prev }
          delete next[editDialog.name]
          return next
        })
        setEditDialog(null)
        notify('Compétence mise à jour', { type: 'success' })
      } else {
        notify('Erreur lors de la mise à jour', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setSavingSkill(false)
    }
  }

  const handleDeleteSkill = async (name: string) => {
    try {
      const res = await agentFetch(`/skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
      if (res.ok) {
        setSkills((prev) => prev.filter((s) => s.name !== name))
        notify('Compétence supprimée', { type: 'success' })
      } else {
        notify('Erreur lors de la suppression', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
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
      <Typography variant="h5" mb={2}>
        Paramètres de l'agent
      </Typography>

      <Card>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ borderBottom: 1, borderColor: 'divider' }}>
          <Tab label="Personnalité" />
          <Tab label="Instructions" />
          <Tab label="Compétences" />
          <Tab label="Proactions" />
        </Tabs>

        {/* Personality tab */}
        {tab === 0 && (
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
        )}

        {/* Instructions tab */}
        {tab === 1 && (
          <CardContent>
            <Typography variant="body2" color="text.secondary" mb={2}>
              Directives qui guident Maggie lors de la planification automatique (quand agir).
            </Typography>

            {instructions.length > 0 && (
              <TableContainer sx={{ mb: 2 }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Contenu</TableCell>
                      <TableCell>Créée le</TableCell>
                      <TableCell width={60} />
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {instructions.map((i) => (
                      <TableRow key={i.id}>
                        <TableCell>
                          <Typography variant="body2">{i.content}</Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2">{formatDateTime(i.createdAt)}</Typography>
                        </TableCell>
                        <TableCell>
                          <IconButton size="small" color="error" onClick={() => handleDeleteInstruction(i.id)}>
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}

            <Stack direction="row" spacing={2} alignItems="flex-start">
              <TextField
                label="Nouvelle instruction"
                value={newInstructionContent}
                onChange={(e) => setNewInstructionContent(e.target.value)}
                multiline
                minRows={2}
                maxRows={4}
                fullWidth
                size="small"
                placeholder="Ex: Envoie-moi un résumé de ma journée chaque matin à 9h"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault()
                    handleAddInstruction()
                  }
                }}
              />
              <Button
                variant="contained"
                startIcon={addingInstruction ? <CircularProgress size={16} /> : <AddIcon />}
                onClick={handleAddInstruction}
                disabled={addingInstruction || !newInstructionContent.trim()}
                sx={{ minWidth: 120, mt: '4px !important' }}
              >
                Ajouter
              </Button>
            </Stack>
          </CardContent>
        )}

        {/* Skills tab */}
        {tab === 2 && (
          <CardContent>
            <Typography variant="body2" color="text.secondary" mb={2}>
              Procédures qui enseignent à Maggie comment réaliser des tâches spécifiques.
            </Typography>

            {skills.length > 0 && (
              <TableContainer sx={{ mb: 2 }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Nom</TableCell>
                      <TableCell>Description</TableCell>
                      <TableCell>Tags</TableCell>
                      <TableCell width={120} />
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {skills.map((s) => (
                      <>
                        <TableRow key={s.name}>
                          <TableCell>
                            <Typography variant="body2" fontWeight="medium">
                              {s.name}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">{s.description}</Typography>
                          </TableCell>
                          <TableCell>
                            <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                              {s.tags.map((tag) => (
                                <Chip key={tag} label={tag} size="small" variant="outlined" />
                              ))}
                            </Stack>
                          </TableCell>
                          <TableCell>
                            <Stack direction="row" spacing={0.5}>
                              <IconButton
                                size="small"
                                onClick={() => handleExpandSkill(s.name)}
                                sx={{
                                  transform: expandedSkill === s.name ? 'rotate(180deg)' : 'none',
                                  transition: 'transform 0.2s',
                                }}
                              >
                                <ExpandMoreIcon fontSize="small" />
                              </IconButton>
                              <IconButton size="small" onClick={() => handleEditSkill(s.name)}>
                                <EditIcon fontSize="small" />
                              </IconButton>
                              <IconButton
                                size="small"
                                color="error"
                                onClick={() => handleDeleteSkill(s.name)}
                              >
                                <DeleteIcon fontSize="small" />
                              </IconButton>
                            </Stack>
                          </TableCell>
                        </TableRow>
                        <TableRow key={`${s.name}-content`}>
                          <TableCell colSpan={4} sx={{ py: 0, borderBottom: expandedSkill === s.name ? undefined : 'none' }}>
                            <Collapse in={expandedSkill === s.name} timeout="auto" unmountOnExit>
                              <Box sx={{ py: 2 }}>
                                <Typography
                                  variant="body2"
                                  component="pre"
                                  sx={{ whiteSpace: 'pre-wrap', fontFamily: 'monospace', bgcolor: 'grey.50', p: 2, borderRadius: 1 }}
                                >
                                  {skillContent[s.name] || 'Chargement...'}
                                </Typography>
                              </Box>
                            </Collapse>
                          </TableCell>
                        </TableRow>
                      </>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}

            <Typography variant="subtitle2" mb={1}>
              Ajouter une compétence
            </Typography>
            <Stack spacing={2}>
              <Stack direction="row" spacing={2}>
                <TextField
                  label="Nom"
                  value={skillForm.name}
                  onChange={(e) => setSkillForm((f) => ({ ...f, name: e.target.value }))}
                  size="small"
                  sx={{ flex: 1 }}
                  placeholder="concert-event-link"
                />
                <TextField
                  label="Tags (séparés par des virgules)"
                  value={skillForm.tags}
                  onChange={(e) => setSkillForm((f) => ({ ...f, tags: e.target.value }))}
                  size="small"
                  sx={{ flex: 1 }}
                  placeholder="concert, calendrier, lien"
                />
              </Stack>
              <TextField
                label="Description"
                value={skillForm.description}
                onChange={(e) => setSkillForm((f) => ({ ...f, description: e.target.value }))}
                size="small"
                fullWidth
                placeholder="Rechercher et ajouter un lien web lors de l'ajout d'un concert"
              />
              <TextField
                label="Contenu"
                value={skillForm.content}
                onChange={(e) => setSkillForm((f) => ({ ...f, content: e.target.value }))}
                multiline
                minRows={4}
                maxRows={10}
                fullWidth
                size="small"
                placeholder="Procédure détaillée..."
              />
              <Box display="flex" justifyContent="flex-end">
                <Button
                  variant="contained"
                  startIcon={addingSkill ? <CircularProgress size={16} /> : <AddIcon />}
                  onClick={handleAddSkill}
                  disabled={addingSkill || !skillForm.name.trim() || !skillForm.content.trim()}
                >
                  Créer
                </Button>
              </Box>
            </Stack>
          </CardContent>
        )}

        {/* Proactions tab */}
        {tab === 3 && (
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
        )}
      </Card>

      {/* Skill edit dialog */}
      <Dialog open={!!editDialog} onClose={() => setEditDialog(null)} maxWidth="md" fullWidth>
        <DialogTitle>Modifier la compétence</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Nom"
              value={editDialog?.name || ''}
              disabled
              size="small"
              fullWidth
            />
            <TextField
              label="Description"
              value={editDialog?.description || ''}
              onChange={(e) => setEditDialog((d) => d ? { ...d, description: e.target.value } : d)}
              size="small"
              fullWidth
            />
            <TextField
              label="Tags (séparés par des virgules)"
              value={editDialog?.tags || ''}
              onChange={(e) => setEditDialog((d) => d ? { ...d, tags: e.target.value } : d)}
              size="small"
              fullWidth
            />
            <TextField
              label="Contenu"
              value={editDialog?.content || ''}
              onChange={(e) => setEditDialog((d) => d ? { ...d, content: e.target.value } : d)}
              multiline
              minRows={6}
              maxRows={15}
              fullWidth
              size="small"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditDialog(null)}>Annuler</Button>
          <Button
            variant="contained"
            onClick={handleSaveSkillEdit}
            disabled={savingSkill}
            startIcon={savingSkill ? <CircularProgress size={16} /> : undefined}
          >
            Enregistrer
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
