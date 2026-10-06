import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import CircularProgress from '@mui/material/CircularProgress'
import CheckIcon from '@mui/icons-material/Check'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline'
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty'
import type { ToolCallState } from './types'

interface ToolCallListProps {
  toolCalls: ToolCallState[]
}

/**
 * What Maggie has been doing, in the Mind panel's "Activité" section.
 *
 * `mind-activity`, `mind-tool-call` and `data-status` are the handles the chat
 * journey scopes to (MAG-99), like `page-content` and `chat-panel` before them:
 * a tool's outcome is drawn as an icon with no accessible name, so without the
 * attribute a journey could only assert that the tool was *mentioned*, never
 * that it succeeded.
 */
export const ToolCallList = ({ toolCalls }: ToolCallListProps) => {
  if (toolCalls.length === 0) {
    return (
      <Typography data-testid="mind-activity" variant="caption" color="text.secondary" sx={{ px: 2, py: 1 }}>
        Aucune activité
      </Typography>
    )
  }

  return (
    <Box data-testid="mind-activity" sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
      {toolCalls.map((tc) => (
        <Box
          key={tc.toolCallId}
          data-testid="mind-tool-call"
          data-status={tc.status}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            px: 2,
            py: 0.5,
          }}
        >
          {tc.status === 'running' && <CircularProgress size={14} />}
          {tc.status === 'success' && <CheckIcon sx={{ fontSize: 14, color: 'success.main' }} />}
          {tc.status === 'error' && <ErrorOutlineIcon sx={{ fontSize: 14, color: 'error.main' }} />}
          {tc.status === 'pending_approval' && (
            <HourglassEmptyIcon titleAccess="En attente de validation" sx={{ fontSize: 14, color: 'warning.main' }} />
          )}
          <Typography variant="body2" noWrap sx={{ flex: 1, fontFamily: 'monospace', fontSize: 12 }}>
            {tc.toolName}
          </Typography>
        </Box>
      ))}
    </Box>
  )
}
