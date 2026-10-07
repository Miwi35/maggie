import Box from '@mui/material/Box'
import { alpha, keyframes } from '@mui/material/styles'
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
}

/** One message: the owner's on the right in the accent, Maggie's on the left in her own surface. */
export const ChatBubble = ({ role, children, id, onClick, highlighted = false, animate = true }: ChatBubbleProps) => {
  const isUser = role === 'user'

  return (
    <Box
      id={id}
      data-role={role}
      onClick={onClick}
      sx={(theme) => ({
        alignSelf: isUser ? 'flex-end' : 'flex-start',
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
}
