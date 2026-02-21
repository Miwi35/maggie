import { useState, useCallback, useRef, useMemo } from 'react'
import { Layout as RALayout, LayoutProps } from 'react-admin'
import Box from '@mui/material/Box'
import { ChatWidget, ChatWidgetRef } from '../chat/ChatWidget'
import { CustomAppBar } from './AppBar'
import { CustomMenu } from './Menu'
import { ChatContext } from './ChatContext'

export const Layout = (props: LayoutProps) => {
  const [chatOpen, setChatOpen] = useState(true)
  const [unreadChat, setUnreadChat] = useState(false)
  const chatRef = useRef<ChatWidgetRef>(null)

  const triggerResize = useCallback(() => {
    // FullCalendar listens for window resize to recalculate column widths
    setTimeout(() => window.dispatchEvent(new Event('resize')), 250)
  }, [])

  const handleChatToggle = useCallback(() => {
    setChatOpen((prev) => {
      if (!prev) setUnreadChat(false)
      return !prev
    })
    triggerResize()
  }, [triggerResize])

  const handleChatClose = useCallback(() => {
    setChatOpen(false)
    triggerResize()
  }, [triggerResize])

  const handleUnread = useCallback(() => {
    setUnreadChat(true)
  }, [])

  const handleVoiceMessage = useCallback(
    (text: string) => {
      if (!chatOpen) {
        setChatOpen(true)
        setUnreadChat(false)
      }
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
      <RALayout {...props} menu={CustomMenu} appBar={CustomAppBar}>
        <Box sx={{ display: 'flex', flex: 1, minHeight: 0 }}>
          <Box sx={{ flex: 1, minWidth: 0, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
            {props.children}
          </Box>
          <ChatWidget ref={chatRef} open={chatOpen} onClose={handleChatClose} onUnread={handleUnread} />
        </Box>
      </RALayout>
    </ChatContext.Provider>
  )
}
