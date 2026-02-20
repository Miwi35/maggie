import { useState, useCallback, useRef, useMemo } from 'react'
import { Layout as RALayout, LayoutProps } from 'react-admin'
import Box from '@mui/material/Box'
import { ChatWidget, ChatWidgetRef } from '../chat/ChatWidget'
import { CustomAppBar } from './AppBar'
import { CustomMenu } from './Menu'
import { ChatContext } from './ChatContext'

const SIDEBAR_WIDTH = 380

export const Layout = (props: LayoutProps) => {
  const [chatOpen, setChatOpen] = useState(false)
  const [unreadChat, setUnreadChat] = useState(false)
  const chatRef = useRef<ChatWidgetRef>(null)

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

  const handleVoiceMessage = useCallback(
    (text: string) => {
      if (!chatOpen) {
        setChatOpen(true)
        setUnreadChat(false)
      }
      // Small delay to ensure drawer is open and ref is mounted
      setTimeout(() => {
        chatRef.current?.sendMessage(text)
      }, 100)
    },
    [chatOpen],
  )

  const chatContext = useMemo(
    () => ({ chatOpen, onChatToggle: handleChatToggle, unreadChat, onVoiceMessage: handleVoiceMessage }),
    [chatOpen, handleChatToggle, unreadChat, handleVoiceMessage],
  )

  return (
    <ChatContext.Provider value={chatContext}>
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
            appBar={CustomAppBar}
          />
        </Box>
        <ChatWidget ref={chatRef} open={chatOpen} onClose={handleChatClose} onUnread={handleUnread} />
      </Box>
    </ChatContext.Provider>
  )
}
