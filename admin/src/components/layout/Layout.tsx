import { useState, useCallback, useRef, useMemo } from 'react'
import { Layout as RALayout, LayoutProps } from 'react-admin'
import Box from '@mui/material/Box'
import { ChatWidget, ChatWidgetRef } from '../chat/ChatWidget'
import { CustomAppBar } from './AppBar'
import { CustomMenu } from './Menu'
import { ChatContext } from './ChatContext'
import type { SidebarTab } from './ChatContext'
import type { AgentState, ContextState, ToolCallState } from '../mind/types'

export const Layout = (props: LayoutProps) => {
  const [chatOpen, setChatOpen] = useState(true)
  const [sidebarTab, setSidebarTab] = useState<SidebarTab>('chat')
  const [unreadChat, setUnreadChat] = useState(false)
  const [contexts, setContexts] = useState<ContextState[]>([])
  const [toolCalls, setToolCalls] = useState<ToolCallState[]>([])
  const [agentState, setAgentState] = useState<AgentState>('idle')
  const chatRef = useRef<ChatWidgetRef>(null)

  const triggerResize = useCallback(() => {
    setTimeout(() => window.dispatchEvent(new Event('resize')), 250)
  }, [])

  const handleChatToggle = useCallback(() => {
    if (!chatOpen) {
      setChatOpen(true)
      setSidebarTab('chat')
      setUnreadChat(false)
    } else if (sidebarTab === 'chat') {
      setChatOpen(false)
    } else {
      setSidebarTab('chat')
      setUnreadChat(false)
    }
    triggerResize()
  }, [chatOpen, sidebarTab, triggerResize])

  const handleMindToggle = useCallback(() => {
    if (!chatOpen) {
      setChatOpen(true)
      setSidebarTab('mind')
    } else if (sidebarTab === 'mind') {
      setChatOpen(false)
    } else {
      setSidebarTab('mind')
    }
    triggerResize()
  }, [chatOpen, sidebarTab, triggerResize])

  const handleChatClose = useCallback(() => {
    setChatOpen(false)
    triggerResize()
  }, [triggerResize])

  const handleUnread = useCallback(() => {
    setUnreadChat(true)
  }, [])

  const handleTabChange = useCallback((_: unknown, newTab: SidebarTab) => {
    setSidebarTab(newTab)
  }, [])

  const handleVoiceMessage = useCallback(
    (text: string) => {
      if (!chatOpen) {
        setChatOpen(true)
        setUnreadChat(false)
      }
      setSidebarTab('chat')
      setTimeout(() => {
        chatRef.current?.sendMessage(text)
      }, 100)
    },
    [chatOpen],
  )

  const chatContext = useMemo(
    () => ({
      chatOpen,
      sidebarTab,
      onChatToggle: handleChatToggle,
      onMindToggle: handleMindToggle,
      unreadChat,
      onVoiceMessage: handleVoiceMessage,
    }),
    [
      chatOpen,
      sidebarTab,
      handleChatToggle,
      handleMindToggle,
      unreadChat,
      handleVoiceMessage,
    ],
  )

  return (
    <ChatContext.Provider value={chatContext}>
      <RALayout {...props} menu={CustomMenu} appBar={CustomAppBar}>
        <Box sx={{ display: 'flex', flex: 1, minHeight: 0 }}>
          {/* `page-content` and the chat panel's `chat-panel` are the two
              handles the e2e journeys scope their locators to (MAG-97).
              React-admin puts the sidebar, the page and the chat side by side
              inside one <main>, so the landmark alone cannot tell them apart —
              and Maggie's answers quote the page's own wording often enough
              that an unscoped getByText matches twice. */}
          <Box
            data-testid="page-content"
            sx={{ flex: 1, minWidth: 0, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}
          >
            {props.children}
          </Box>
          <ChatWidget
            ref={chatRef}
            open={chatOpen}
            sidebarTab={sidebarTab}
            onTabChange={handleTabChange}
            onClose={handleChatClose}
            onUnread={handleUnread}
            agentState={agentState}
            onAgentStateChange={setAgentState}
            contexts={contexts}
            onContextsChange={setContexts}
            toolCalls={toolCalls}
            onToolCallsChange={setToolCalls}
          />
        </Box>
      </RALayout>
    </ChatContext.Provider>
  )
}
