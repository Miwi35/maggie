import { useState } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import { alpha, keyframes } from '@mui/material/styles'
import { NARROW_QUERY } from '../../breakpoints'
import type { ReactNode } from 'react'

const slideIn = keyframes`
  from { transform: translateY(8px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
`
const highlightFade = (color: string) => keyframes`
  0% { box-shadow: 0 0 0 3px ${color}; }
  100% { box-shadow: 0 0 0 0 transparent; }
`

interface ChatBubbleProps {
  role: 'user' | 'assistant'
  children: ReactNode
  id?: string
  onClick?: () => void
  /** Ring that fades out, for the message a search result jumped to. */
  highlighted?: boolean
  /** Off for the message that replaces its own streaming bubble, so it does not slide in twice. */
  animate?: boolean
  /** Adds a « more » menu with « Supprimer » beside the bubble (MAG-342). */
  onDelete?: () => void
  /** Adds « Modifier » to that menu: only the last message of the owner can be rewritten (MAG-320). */
  onEdit?: () => void
}

/** The trace of the owner's answer to a request of Maggie's (MAG-336): a discreet line in the thread, not a message. */
export const DecisionLine = ({
  id,
  actionId,
  children,
}: {
  id?: string
  /** The held action it answers — the handle a journey scopes to. */
  actionId?: string
  children: ReactNode
}) => (
  <Box
    id={id}
    data-role="decision"
    data-testid="chat-decision"
    data-action-id={actionId}
    sx={{
      alignSelf: 'center',
      maxWidth: '85%',
      px: 1,
      py: 0.5,
      color: 'text.secondary',
      typography: 'caption',
      textAlign: 'center',
      wordBreak: 'break-word',
      overflowWrap: 'anywhere',
    }}
  >
    {children}
  </Box>
)

/** One message: the owner's on the right in the accent, Maggie's on the left in her own surface. */
export const ChatBubble = ({
  role,
  children,
  id,
  onClick,
  highlighted = false,
  animate = true,
  onDelete,
  onEdit,
}: ChatBubbleProps) => {
  const isUser = role === 'user'
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)

  const bubble = (
    <Box
      id={id}
      data-role={role}
      onClick={onClick}
      sx={(theme) => ({
        ...(!onDelete && { alignSelf: isUser ? 'flex-end' : 'flex-start' }),
        maxWidth: '85%',
        px: '14px',
        py: '10px',
        borderRadius: isUser ? '22px 22px 6px 22px' : '22px 22px 22px 6px',
        bgcolor: isUser ? alpha(theme.palette.primary.main, 0.18) : theme.palette.maggie.reply,
        color: 'text.primary',
        typography: 'body2',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        overflowWrap: 'anywhere',
        transition: 'box-shadow 0.3s ease',
        ...(animate && { animation: `${slideIn} 420ms cubic-bezier(0.2, 0.9, 0.3, 1) both` }),
        ...(highlighted && {
          boxShadow: `0 0 0 2px ${theme.palette.warning.main}`,
          animation: `${animate ? `${slideIn} 420ms cubic-bezier(0.2, 0.9, 0.3, 1) both, ` : ''}${highlightFade(theme.palette.warning.main)} 2s ease-out`,
        }),
        '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
      })}
    >
      {children}
    </Box>
  )

  if (!onDelete) return bubble

  // Beside the bubble, on the side facing the page: shown on hover or focus with a
  // mouse, always there where there is no hover to reveal it. 48px under `md`.
  return (
    <Box
      sx={{
        alignSelf: isUser ? 'flex-end' : 'flex-start',
        maxWidth: '100%',
        display: 'flex',
        flexDirection: isUser ? 'row-reverse' : 'row',
        alignItems: 'center',
        '& .bubble-menu': { opacity: 1 },
        '@media (hover: hover) and (pointer: fine)': {
          '& .bubble-menu': { opacity: 0 },
          '&:hover .bubble-menu, & .bubble-menu:focus-visible, & .bubble-menu[aria-expanded="true"]': {
            opacity: 1,
          },
        },
      }}
    >
      {bubble}
      <IconButton
        className="bubble-menu"
        size="small"
        aria-label="Actions du message"
        aria-haspopup="menu"
        aria-expanded={anchor ? true : undefined}
        onClick={(e) => setAnchor(e.currentTarget)}
        sx={{
          flexShrink: 0,
          color: 'text.secondary',
          [`@media ${NARROW_QUERY}`]: { width: 48, height: 48 },
        }}
      >
        <MoreVertIcon fontSize="small" />
      </IconButton>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
        {onEdit && (
          <MenuItem
            data-testid="chat-edit-last"
            onClick={() => {
              setAnchor(null)
              onEdit()
            }}
          >
            <ListItemIcon>
              <EditOutlinedIcon fontSize="small" />
            </ListItemIcon>
            <ListItemText>Modifier</ListItemText>
          </MenuItem>
        )}
        <MenuItem
          onClick={() => {
            setAnchor(null)
            onDelete()
          }}
        >
          <ListItemIcon>
            <DeleteOutlineIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText>Supprimer</ListItemText>
        </MenuItem>
      </Menu>
    </Box>
  )
}
