import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { ContextState } from './types'

interface ContextListProps {
  contexts: ContextState[]
}

const statusConfig = {
  active: { color: '#4caf50', icon: '●' },
  dormant: { color: '#ff9800', icon: '◐' },
  closed: { color: '#9e9e9e', icon: '○' },
}

export const ContextList = ({ contexts }: ContextListProps) => {
  if (contexts.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary" sx={{ px: 2, py: 1 }}>
        Aucun contexte actif
      </Typography>
    )
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
      {contexts.map((ctx) => {
        const config = statusConfig[ctx.status]
        return (
          <Box
            key={ctx.id}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              px: 2,
              py: 0.5,
              '&:hover': { bgcolor: 'action.hover' },
              borderRadius: 1,
            }}
          >
            <Typography sx={{ color: config.color, fontSize: 12, lineHeight: 1 }}>
              {config.icon}
            </Typography>
            <Typography variant="body2" noWrap sx={{ flex: 1 }}>
              {ctx.label}
            </Typography>
          </Box>
        )
      })}
    </Box>
  )
}
