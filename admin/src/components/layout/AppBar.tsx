import { AppBar as RAAppBar } from 'react-admin'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Badge from '@mui/material/Badge'
import ChatIcon from '@mui/icons-material/Chat'
import { NotificationBell } from '../notifications/NotificationBell'

interface CustomAppBarProps {
  chatOpen: boolean
  onChatToggle: () => void
  unreadChat: boolean
}

export const CustomAppBar = ({ chatOpen, onChatToggle, unreadChat }: CustomAppBarProps) => (
  <RAAppBar
    toolbar={
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        <NotificationBell />
        <IconButton color="inherit" onClick={onChatToggle}>
          <Badge variant="dot" color="error" invisible={!unreadChat || chatOpen}>
            <ChatIcon />
          </Badge>
        </IconButton>
      </Box>
    }
  />
)
