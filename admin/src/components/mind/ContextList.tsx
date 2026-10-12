import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { NARROW_QUERY } from '../../breakpoints'
import { contextStateColor } from '../../design/tokens'
import type { ContextState } from './types'

interface ContextListProps {
  contexts: ContextState[]
  /** Adds a « Supprimer » button at the end of each row (MAG-342). */
  onDelete?: (context: ContextState) => void
}

// Exhaustive on purpose: a status added to `ContextState` has to be given a
// glyph here, rather than rendering nothing at all.
const statusIcons: Record<ContextState['status'], string> = {
  active: '●',
  dormant: '◐',
  closed: '○',
}

/**
 * The conversation threads Maggie is holding, listed in the « Fils de discussion »
 * dialog opened from the chat header. `mind-contexts` and `mind-context` are what
 * the chat journey counts to tell a change of subject from a follow-up (MAG-99).
 *
 * A thread long enough to have been summarized shows what it is about under its
 * label (MAG-11). That summary is also what Maggie carries into her system
 * prompt, so this line is the owner's only way to see what she remembers of a
 * conversation that scrolled past the history she is sent.
 */
export const ContextList = ({ contexts, onDelete }: ContextListProps) => {
  if (contexts.length === 0) {
    return (
      <Typography data-testid="mind-contexts" variant="caption" color="text.secondary" sx={{ px: 2, py: 1 }}>
        Aucun fil de discussion
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
                // Two lines at most: the dialog can be a phone's whole screen, and a
                // five-line summary per thread would push the other threads off it.
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
            {onDelete ? (
              // Apart, at the end of the row; 48px under `md` for a finger.
              <IconButton
                size="small"
                aria-label={`Supprimer le fil « ${ctx.label} »`}
                onClick={() => onDelete(ctx)}
                sx={{
                  alignSelf: 'center',
                  flexShrink: 0,
                  color: 'text.secondary',
                  '&:hover': { color: 'error.main' },
                  [`@media ${NARROW_QUERY}`]: { width: 48, height: 48 },
                }}
              >
                <DeleteOutlineIcon fontSize="small" />
              </IconButton>
            ) : null}
          </Box>
        )
      })}
    </Box>
  )
}
