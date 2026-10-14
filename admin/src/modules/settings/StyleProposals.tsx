import { useCallback, useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { agentFetch } from './agentFetch'

interface StyleProposal {
  id: string
  content: string
  reason: string | null
  status: 'pending' | 'accepted' | 'rejected'
  createdAt: string | null
}

interface AcceptedInstruction {
  id: string
  content: string
  kind: 'planning' | 'behavior'
  createdAt: string | null
  updatedAt: string | null
}

interface StyleProposalsProps {
  /** The behaviour directive an accepted proposal became, for the directives table. */
  onAccepted: (instruction: AcceptedInstruction) => void
}

/**
 * The style directives Maggie drew from the owner's thumbs, waiting for their word (MAG-23).
 *
 * Nothing here is applied until « Accepter »: that is what turns a proposal into a behaviour
 * directive. The owner may reword it first. A refused proposal leaves, and the synthesis is
 * told so it does not offer it again.
 */
export const StyleProposals = ({ onAccepted }: StyleProposalsProps) => {
  const notify = useNotify()
  const [proposals, setProposals] = useState<StyleProposal[]>([])
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [analysing, setAnalysing] = useState(false)

  const load = useCallback(async () => {
    try {
      const res = await agentFetch('/style-proposals')
      if (res.ok) setProposals(await res.json())
    } catch {
      // The rest of the settings stays usable without the proposals.
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const decide = async (proposal: StyleProposal, action: 'accept' | 'reject') => {
    setBusy(proposal.id)
    try {
      const draft = drafts[proposal.id]?.trim()
      const res = await agentFetch(`/style-proposals/${encodeURIComponent(proposal.id)}/${action}`, {
        method: 'POST',
        ...(action === 'accept' && draft && draft !== proposal.content ? { body: JSON.stringify({ content: draft }) } : {}),
      })
      if (!res.ok && res.status !== 409) throw new Error(`HTTP ${res.status}`)
      if (res.ok && action === 'accept') onAccepted((await res.json()).instruction)
      setProposals((prev) => prev.filter((p) => p.id !== proposal.id))
      notify(action === 'accept' ? 'Directive ajoutée' : 'Proposition écartée', { type: 'success' })
    } catch {
      notify("La proposition n'a pas pu être enregistrée", { type: 'error' })
    } finally {
      setBusy(null)
    }
  }

  const analyse = async () => {
    setAnalysing(true)
    try {
      const res = await agentFetch('/style-proposals/generate', { method: 'POST' })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const fresh: StyleProposal[] = await res.json()
      if (fresh.length === 0) notify('Rien de nouveau à proposer', { type: 'info' })
      await load()
    } catch {
      notify("L'analyse des retours a échoué", { type: 'error' })
    } finally {
      setAnalysing(false)
    }
  }

  return (
    <Box mb={3} data-testid="style-proposals">
      <Stack direction="row" alignItems="center" justifyContent="space-between" mb={1}>
        <Typography variant="subtitle2">Propositions tirées de vos retours</Typography>
        <Button
          size="small"
          onClick={analyse}
          disabled={analysing}
          startIcon={analysing ? <CircularProgress size={14} /> : undefined}
        >
          Analyser mes retours
        </Button>
      </Stack>
      {proposals.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Aucune proposition en attente. Les pouces sur les réponses de Maggie en font naître chaque jour.
        </Typography>
      ) : (
        <Stack spacing={1}>
          {proposals.map((proposal) => (
            <Paper key={proposal.id} variant="outlined" sx={{ p: 1.5 }} data-testid="style-proposal">
              <TextField
                value={drafts[proposal.id] ?? proposal.content}
                onChange={(e) => setDrafts((prev) => ({ ...prev, [proposal.id]: e.target.value }))}
                size="small"
                fullWidth
                multiline
                slotProps={{ htmlInput: { 'aria-label': 'Directive proposée' } }}
              />
              {proposal.reason && (
                <Typography variant="caption" color="text.secondary" component="div" mt={0.5}>
                  {proposal.reason}
                </Typography>
              )}
              <Stack direction="row" spacing={1} justifyContent="flex-end" mt={1}>
                <Button size="small" color="inherit" disabled={busy === proposal.id} onClick={() => decide(proposal, 'reject')}>
                  Refuser
                </Button>
                <Button
                  size="small"
                  variant="contained"
                  disabled={busy === proposal.id || !(drafts[proposal.id] ?? proposal.content).trim()}
                  onClick={() => decide(proposal, 'accept')}
                >
                  Accepter
                </Button>
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}
    </Box>
  )
}
