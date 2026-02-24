import { useState, useCallback, useRef, useMemo } from 'react'
import { Layout as RALayout, LayoutProps } from 'react-admin'
import Box from '@mui/material/Box'
import { ChatWidget, ChatWidgetRef } from '../chat/ChatWidget'
import { CustomAppBar } from './AppBar'
import { CustomMenu } from './Menu'
import { ChatContext } from './ChatContext'
import { useWakeWord } from '../../hooks/useWakeWord'

export const Layout = (props: LayoutProps) => {
  const [chatOpen, setChatOpen] = useState(true)
  const [unreadChat, setUnreadChat] = useState(false)
  const [wakeWordTriggered, setWakeWordTriggered] = useState(false)
  const chatRef = useRef<ChatWidgetRef>(null)

  const triggerResize = useCallback(() => {
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

  const handleWakeWordDetected = useCallback(() => {
    if (!chatOpen) {
      setChatOpen(true)
      setUnreadChat(false)
      triggerResize()
    }
    setWakeWordTriggered(true)
  }, [chatOpen, triggerResize])

  const clearWakeWordTrigger = useCallback(() => {
    setWakeWordTriggered(false)
  }, [])

  const wakeWord = useWakeWord({ onDetected: handleWakeWordDetected })

  const chatContext = useMemo(
    () => ({
      chatOpen,
      onChatToggle: handleChatToggle,
      unreadChat,
      onVoiceMessage: handleVoiceMessage,
      wakeWordEnabled: wakeWord.enabled,
      wakeWordListening: wakeWord.isListening,
      wakeWordTriggered,
      toggleWakeWord: wakeWord.toggleEnabled,
      pauseWakeWord: wakeWord.pause,
      resumeWakeWord: wakeWord.resume,
      clearWakeWordTrigger,
    }),
    [
      chatOpen,
      handleChatToggle,
      unreadChat,
      handleVoiceMessage,
      wakeWord.enabled,
      wakeWord.isListening,
      wakeWordTriggered,
      wakeWord.toggleEnabled,
      wakeWord.pause,
      wakeWord.resume,
      clearWakeWordTrigger,
    ],
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
