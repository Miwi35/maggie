import { useState, useCallback } from 'react'
import { Layout as RALayout, LayoutProps } from 'react-admin'
import Box from '@mui/material/Box'
import { ChatWidget } from '../chat/ChatWidget'
import { CustomAppBar } from './AppBar'
import { CustomMenu } from './Menu'

const SIDEBAR_WIDTH = 380

export const Layout = (props: LayoutProps) => {
  const [chatOpen, setChatOpen] = useState(false)
  const [unreadChat, setUnreadChat] = useState(false)

  const handleChatToggle = useCallback(() => {
    setChatOpen((prev) => {
      if (!prev) setUnreadChat(false)
      return !prev
    })
  }, [])

  const handleChatClose = useCallback(() => {
    setChatOpen(false)
  }, [])

  const handleUnread = useCallback(() => {
    setUnreadChat(true)
  }, [])

  return (
    <Box sx={{ display: 'flex' }}>
      <Box
        sx={{
          flex: 1,
          transition: 'margin-right 225ms cubic-bezier(0, 0, 0.2, 1)',
          marginRight: chatOpen ? `${SIDEBAR_WIDTH}px` : 0,
        }}
      >
        <RALayout
          {...props}
          menu={CustomMenu}
          appBar={() => (
            <CustomAppBar
              chatOpen={chatOpen}
              onChatToggle={handleChatToggle}
              unreadChat={unreadChat}
            />
          )}
        />
      </Box>
      <ChatWidget open={chatOpen} onClose={handleChatClose} onUnread={handleUnread} />
    </Box>
  )
}
