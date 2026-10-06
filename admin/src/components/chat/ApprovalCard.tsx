import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import { isOverdue, type ApprovalItem } from '../../hooks/useApprovals'

interface ApprovalCardProps {
  approval: ApprovalItem
  onApprove: (id: string) => void
  onDeny: (id: string) => void
}

type CardState = 'pending' | 'running' | 'approved' | 'denied' | 'failed' | 'expired'

const STATE_LABELS: Record<CardState, string> = {
  pending: 'En attente',
  running: 'En cours',
  approved: 'Validée',
  denied: 'Refusée',
  failed: 'Échouée',
  expired: 'Expirée',
}

const STATE_COLORS: Record<CardState, 'warning' | 'info' | 'success' | 'default' | 'error'> = {
  pending: 'warning',
  running: 'info',
  approved: 'success',
  denied: 'default',
  failed: 'error',
  expired: 'default',
}

function cardState(approval: ApprovalItem): CardState {
  // `approved` is published when the call is claimed, before its result is
  // known: until `result` arrives the action is still running.
  if (approval.busy || (approval.status === 'approved' && approval.result === null)) return 'running'
  if (approval.status === 'pending') return isOverdue(approval) ? 'expired' : 'pending'
  return approval.status
}

function formatDateTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function formatValue(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value)
}

/** The error the tool answered with, when the action failed. */
function failureReason(result: string | null): string | null {
  if (!result) return null
  try {
    const parsed: unknown = JSON.parse(result)
    if (typeof parsed === 'object' && parsed !== null && 'error' in parsed) {
      return formatValue((parsed as { error: unknown }).error)
    }
  } catch {
    // Not JSON: nothing readable to add.
  }
  return null
}

/**
 * An action Maggie wants to take and waits for the user to allow (MAG-6).
 *
 * `approval-card` and `data-status` are the handles the journeys scope to, as
 * on the Mind panel's tool calls.
 */
export const ApprovalCard = ({ approval, onApprove, onDeny }: ApprovalCardProps) => {
  const state = cardState(approval)
  const args = Object.entries(approval.arguments ?? {})
  const reason = state === 'failed' ? failureReason(approval.result) : null

  return (
    <Box
      data-testid="approval-card"
      data-status={state}
      role="group"
      aria-label={`Validation : ${approval.toolName}`}
      sx={{
        alignSelf: 'stretch',
        border: 1,
        borderColor: state === 'pending' ? 'warning.main' : 'divider',
        borderRadius: 2,
        bgcolor: 'background.paper',
        p: 1.5,
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Typography variant="caption" color="text.secondary" sx={{ flex: 1 }}>
          Maggie demande ton accord
        </Typography>
        <Chip
          size="small"
          color={STATE_COLORS[state]}
          label={STATE_LABELS[state]}
          icon={state === 'running' ? <CircularProgress size={12} color="inherit" /> : undefined}
        />
      </Box>

      <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
        {approval.toolName}
      </Typography>

      {args.length > 0 && (
        <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 1, rowGap: 0.25 }}>
          {args.map(([key, value]) => (
            <Box key={key} sx={{ display: 'contents' }}>
              <Typography component="dt" variant="caption" color="text.secondary">
                {key}
              </Typography>
              <Typography component="dd" variant="caption" sx={{ m: 0, wordBreak: 'break-word' }}>
                {formatValue(value)}
              </Typography>
            </Box>
          ))}
        </Box>
      )}

      <Typography variant="caption" color="text.secondary">
        Demandée le {formatDateTime(approval.createdAt)} · expire le {formatDateTime(approval.expiresAt)}
      </Typography>

      {reason && (
        <Typography variant="caption" color="error">
          {reason}
        </Typography>
      )}

      {approval.error && (
        <Typography role="alert" variant="caption" color="error">
          {approval.error}
        </Typography>
      )}

      {(state === 'pending' || state === 'running') && (
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button
            size="small"
            variant="contained"
            disabled={state === 'running'}
            onClick={() => onApprove(approval.id)}
          >
            Autoriser
          </Button>
          <Button size="small" variant="outlined" disabled={state === 'running'} onClick={() => onDeny(approval.id)}>
            Refuser
          </Button>
        </Box>
      )}
    </Box>
  )
}
