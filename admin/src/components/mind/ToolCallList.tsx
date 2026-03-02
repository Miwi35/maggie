import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import CircularProgress from '@mui/material/CircularProgress'
import CheckIcon from '@mui/icons-material/Check'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline'
import type { ToolCallState } from './types'

interface ToolCallListProps {
  toolCalls: ToolCallState[]
}

export const ToolCallList = ({ toolCalls }: ToolCallListProps) => {
  if (toolCalls.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary" sx={{ px: 2, py: 1 }}>
        Aucune activité
      </Typography>
    )
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
      {toolCalls.map((tc) => (
        <Box
          key={tc.toolCallId}
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
          <Typography variant="body2" noWrap sx={{ flex: 1, fontFamily: 'monospace', fontSize: 12 }}>
            {tc.toolName}
          </Typography>
        </Box>
      ))}
    </Box>
  )
}
