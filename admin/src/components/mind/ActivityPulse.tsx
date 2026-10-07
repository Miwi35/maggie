import Box from '@mui/material/Box'
import { TOKENS } from '../../design/tokens'
import type { AgentState } from './types'

interface ActivityPulseProps {
  state: AgentState
}

export const ActivityPulse = ({ state }: ActivityPulseProps) => {
  if (state === 'idle') return null

  // Thinking is Maggie herself, so it is her violet; working is a tool running,
  // which is the signal the rest of the app uses for « in progress » (MAG-39).
  const color = state === 'thinking' ? 'primary.main' : TOKENS.signal.warning
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
