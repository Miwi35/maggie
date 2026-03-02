import Box from '@mui/material/Box'
import type { AgentState } from './types'

interface ActivityPulseProps {
  state: AgentState
}

export const ActivityPulse = ({ state }: ActivityPulseProps) => {
  if (state === 'idle') return null

  const color = state === 'thinking' ? '#9c27b0' : '#ff9800'
  const speed = state === 'thinking' ? '2s' : '1s'

  return (
    <Box
      sx={{
        width: 10,
        height: 10,
        borderRadius: '50%',
        bgcolor: color,
        animation: `agentPulse ${speed} ease-in-out infinite`,
        '@keyframes agentPulse': {
          '0%, 100%': { opacity: 1, transform: 'scale(1)' },
          '50%': { opacity: 0.4, transform: 'scale(0.8)' },
        },
      }}
    />
  )
}
