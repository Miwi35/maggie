import { useState, useCallback, useRef, useMemo } from 'react'
import { Layout as RALayout, LayoutProps } from 'react-admin'
import Box from '@mui/material/Box'
import { ChatWidget, ChatWidgetRef } from '../chat/ChatWidget'
import { CustomAppBar } from './AppBar'
import { ThemePreferenceSync } from './ThemePreferenceSync'
import { CustomMenu } from './Menu'
import { CustomSidebar } from './Sidebar'
import { ChatContext } from './ChatContext'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import type { SidebarTab } from './ChatContext'
import type { AgentState, ContextState, ToolCallState } from '../mind/types'

export const Layout = (props: LayoutProps) => {
  const isNarrow = useNarrowScreen()
  // Open beside the page on a desk, folded away on a phone or a tablet
  // (MAG-38): below `md` the panel is a sheet over the whole window, and
  // nobody arrives on the admin wanting the conversation on top of it.
  const [chatOpen, setChatOpen] = useState(!isNarrow)
  const [sidebarTab, setSidebarTab] = useState<SidebarTab>('chat')
  const [unreadChat, setUnreadChat] = useState(false)
  const [contexts, setContexts] = useState<ContextState[]>([])
  const [toolCalls, setToolCalls] = useState<ToolCallState[]>([])
  const [agentState, setAgentState] = useState<AgentState>('idle')
  const chatRef = useRef<ChatWidgetRef>(null)

  // Crossing the breakpoint re-decides it: a window narrowed past 900px with
  // the chat open would otherwise be a sheet over a page nobody asked to hide.
  // Adjusted while rendering rather than in an effect — React re-runs this pass
  // before painting, so the panel never flashes in the wrong shape.
  const [wasNarrow, setWasNarrow] = useState(isNarrow)
  if (wasNarrow !== isNarrow) {
    setWasNarrow(isNarrow)
    setChatOpen(!isNarrow)
  }

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
      <ThemePreferenceSync />
      <RALayout {...props} menu={CustomMenu} appBar={CustomAppBar} sidebar={CustomSidebar}>
        <Box sx={{ display: 'flex', flex: 1, minHeight: 0 }}>
          {/* `page-content` and the chat panel's `chat-panel` are the two
              handles the e2e journeys scope their locators to (MAG-97).
              React-admin puts the sidebar, the page and the chat side by side
              inside one <main>, so the landmark alone cannot tell them apart —
              and Maggie's answers quote the page's own wording often enough
              that an unscoped getByText matches twice. */}
          <Box
            data-testid="page-content"
            sx={(theme) => ({
              flex: 1,
              minWidth: 0,
              overflow: 'hidden',
              display: 'flex',
              flexDirection: 'column',
              // A datagrid is wider than a phone and there is no honest way to
              // make a twelve-column table narrow. Below `md` the page carries
              // the sideways scroll itself, instead of clipping the table
              // (`overflow: hidden`) or dragging the whole frame — app bar
              // included — out of the viewport (MAG-38, with `RaLayout` in
              // `src/theme.ts`).
              [theme.breakpoints.down('md')]: { overflowX: 'auto' },
            })}
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
