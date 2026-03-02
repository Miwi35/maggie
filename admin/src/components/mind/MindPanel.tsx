import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import Divider from '@mui/material/Divider'
import CloseIcon from '@mui/icons-material/Close'
import { ActivityPulse } from './ActivityPulse'
import { ContextList } from './ContextList'
import { ToolCallList } from './ToolCallList'
import type { AgentState, ContextState, ToolCallState } from './types'

const PANEL_WIDTH = 280

interface MindPanelProps {
  open: boolean
  contexts: ContextState[]
  toolCalls: ToolCallState[]
  agentState: AgentState
  onClose: () => void
}

export const MindPanel = ({ open, contexts, toolCalls, agentState, onClose }: MindPanelProps) => {
  return (
    <Box
      sx={{
        width: open ? PANEL_WIDTH : 0,
        flexShrink: 0,
        transition: 'width 225ms cubic-bezier(0, 0, 0.2, 1)',
      }}
    >
      <Box
        sx={{
          width: PANEL_WIDTH,
          position: 'fixed',
          top: (theme) => theme.mixins.toolbar.minHeight,
          right: 0,
          bottom: 0,
          display: 'flex',
          flexDirection: 'column',
          borderLeft: 1,
          borderColor: 'divider',
          bgcolor: 'background.paper',
          transform: open ? 'translateX(0)' : `translateX(${PANEL_WIDTH}px)`,
          transition: 'transform 225ms cubic-bezier(0, 0, 0.2, 1)',
          zIndex: 1200,
        }}
      >
        {/* Header */}
        <Box
          sx={{
            px: 2,
            py: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <ActivityPulse state={agentState} />
            <Typography variant="subtitle2" fontWeight={600}>
              Maggie&apos;s Mind
            </Typography>
          </Box>
          <IconButton size="small" onClick={onClose}>
            <CloseIcon fontSize="small" />
          </IconButton>
        </Box>

        {/* Contexts section */}
        <Box sx={{ py: 1 }}>
          <Typography
            variant="overline"
            sx={{ px: 2, color: 'text.secondary', fontSize: 10, letterSpacing: 1 }}
          >
            Contextes
          </Typography>
          <ContextList contexts={contexts} />
        </Box>

        <Divider />

        {/* Activity section */}
        <Box sx={{ py: 1, flex: 1, overflowY: 'auto' }}>
          <Typography
            variant="overline"
            sx={{ px: 2, color: 'text.secondary', fontSize: 10, letterSpacing: 1 }}
          >
            Activité
          </Typography>
          <ToolCallList toolCalls={toolCalls} />
        </Box>
      </Box>
    </Box>
  )
}
