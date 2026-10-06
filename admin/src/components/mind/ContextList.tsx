import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { contextStateColor } from '../../design/tokens'
import type { ContextState } from './types'

interface ContextListProps {
  contexts: ContextState[]
}

const statusIcons: Record<string, string> = {
  active: '●',
  dormant: '◐',
  closed: '○',
}

/**
 * The conversation threads Maggie is holding, in the Mind panel's "Contextes"
 * section. `mind-contexts` and `mind-context` are what the chat journey counts
 * to tell a change of subject from a follow-up (MAG-99).
 *
 * A thread long enough to have been summarized shows what it is about under its
 * label (MAG-11). That summary is also what Maggie carries into her system
 * prompt, so this line is the owner's only way to see what she remembers of a
 * conversation that scrolled past the history she is sent.
 */
export const ContextList = ({ contexts }: ContextListProps) => {
  if (contexts.length === 0) {
    return (
      <Typography data-testid="mind-contexts" variant="caption" color="text.secondary" sx={{ px: 2, py: 1 }}>
        Aucun contexte actif
      </Typography>
    )
  }

  return (
    <Box data-testid="mind-contexts" sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
      {contexts.map((ctx) => {
        return (
          <Box
            key={ctx.id}
            data-testid="mind-context"
            data-status={ctx.status}
            sx={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 1,
              px: 2,
              py: 0.5,
              '&:hover': { bgcolor: 'action.hover' },
              borderRadius: 1,
            }}
          >
            <Typography
              sx={{ color: contextStateColor(ctx.status), fontSize: 12, lineHeight: '20px' }}
            >
              {statusIcons[ctx.status]}
            </Typography>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="body2" noWrap>
                {ctx.label}
              </Typography>
              {ctx.summary ? (
                // Two lines at most: the panel is a sidebar, and a five-line summary
                // per thread would push the activity section off the screen.
                <Typography
                  data-testid="mind-context-summary"
                  variant="caption"
                  color="text.secondary"
                  sx={{
                    display: '-webkit-box',
                    WebkitBoxOrient: 'vertical',
                    WebkitLineClamp: 2,
                    overflow: 'hidden',
                  }}
                >
                  {ctx.summary}
                </Typography>
              ) : null}
            </Box>
          </Box>
        )
      })}
    </Box>
  )
}
