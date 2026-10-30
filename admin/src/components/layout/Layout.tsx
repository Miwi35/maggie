import { Suspense, useState, useCallback, useEffect, useRef, useMemo } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Layout as RALayout, Loading, LayoutProps } from 'react-admin'
import Box from '@mui/material/Box'
import { ChatWidget, ChatWidgetRef } from '../chat/ChatWidget'
import { MaggieInterruptionHost } from '../maggie/MaggieInterruptionHost'
import { MaggiePortrait } from '../maggie/MaggiePortrait'
import { CustomAppBar } from './AppBar'
import { Breadcrumbs, PageHeading } from './Breadcrumbs'
import { ProviderBanner } from './ProviderBanner'
import { ThemePreferenceSync } from './ThemePreferenceSync'
import { CustomMenu } from './Menu'
import { CustomSidebar } from './Sidebar'
import { ChatContext } from './ChatContext'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import type { SidebarTab } from './ChatContext'
import type { AgentState, ContextState, ToolCallState } from '../mind/types'

export const Layout = (props: LayoutProps) => {
  const isNarrow = useNarrowScreen()
  // Folded away everywhere: Maggie is a 64px portrait until someone asks for her
  // (MAG-624). Used to be open beside the page on a desk (MAG-38).
  const [chatOpen, setChatOpen] = useState(false)
  const [sidebarTab, setSidebarTab] = useState<SidebarTab>('chat')
  const [unreadChat, setUnreadChat] = useState(false)
  const [unreadCount, setUnreadCount] = useState(0)
  // A notification of type `message` links to `/chat?message=<id>`: the chat opens on it (MAG-687).
  const [openMessageId, setOpenMessageId] = useState<string | null>(null)
  const location = useLocation()
  const navigate = useNavigate()
  const [contexts, setContexts] = useState<ContextState[]>([])
  const [toolCalls, setToolCalls] = useState<ToolCallState[]>([])
  const [agentState, setAgentState] = useState<AgentState>('idle')
  const chatRef = useRef<ChatWidgetRef>(null)

  // Crossing the breakpoint folds it back: a window narrowed past 900px with
  // the chat open would otherwise be a sheet over a page nobody asked to hide.
  // Adjusted while rendering rather than in an effect — React re-runs this pass
  // before painting, so the panel never flashes in the wrong shape.
  const [wasNarrow, setWasNarrow] = useState(isNarrow)
  if (wasNarrow !== isNarrow) {
    setWasNarrow(isNarrow)
    setChatOpen(false)
  }

  const resizeTimer = useRef<ReturnType<typeof setTimeout>>(undefined)
  useEffect(() => () => clearTimeout(resizeTimer.current), [])
  const triggerResize = useCallback(() => {
    clearTimeout(resizeTimer.current)
    resizeTimer.current = setTimeout(() => window.dispatchEvent(new Event('resize')), 250)
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

  useEffect(() => {
    if (location.pathname !== '/chat') return
    const id = new URLSearchParams(location.search).get('message')
    if (id) {
      setOpenMessageId(id)
      setChatOpen(true)
      setSidebarTab('chat')
      setUnreadChat(false)
      triggerResize()
    }
    navigate('/', { replace: true })
  }, [location.pathname, location.search, navigate, triggerResize])

  // The read position is the server's: read elsewhere, the dot of a live message goes too.
  const handleUnreadCount = useCallback((count: number) => {
    setUnreadCount(count)
    if (count === 0) setUnreadChat(false)
  }, [])

  const handleMessageOpened = useCallback(() => setOpenMessageId(null), [])

  const handleOpenChatFromInterruption = useCallback(() => {
    setChatOpen(true)
    setSidebarTab('chat')
    setUnreadChat(false)
    triggerResize()
  }, [triggerResize])

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
      unreadChat: unreadChat || unreadCount > 0,
      unreadCount,
      onVoiceMessage: handleVoiceMessage,
    }),
    [
      chatOpen,
      sidebarTab,
      handleChatToggle,
      unreadChat,
      unreadCount,
      handleVoiceMessage,
    ],
  )

  return (
    <ChatContext.Provider value={chatContext}>
      <ThemePreferenceSync />
      <RALayout {...props} menu={CustomMenu} appBar={CustomAppBar} sidebar={CustomSidebar}>
        <Box sx={{ display: 'flex', flex: 1, minHeight: 0 }}>
          {/* The breadcrumb sits above `page-content`, not in it: the journeys
              scope their text locators to the page, and a trail repeating the
              page's own title would match twice (MAG-352). */}
          <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
            <ProviderBanner />
            <Breadcrumbs />
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
              {/* The page's own boundary: react-admin's wraps this whole box, chat panel included,
                  so a lazy page still loading would otherwise take the conversation down with it
                  (and a click on the app bar's chat button in that window would close it). */}
              <PageHeading />
              <Suspense fallback={<Loading />}>{props.children}</Suspense>
            </Box>
          </Box>
          <ChatWidget
            ref={chatRef}
            open={chatOpen}
            sidebarTab={sidebarTab}
            onTabChange={handleTabChange}
            onClose={handleChatClose}
            onUnread={handleUnread}
            onUnreadCount={handleUnreadCount}
            openMessageId={openMessageId}
            onMessageOpened={handleMessageOpened}
            agentState={agentState}
            onAgentStateChange={setAgentState}
            contexts={contexts}
            onContextsChange={setContexts}
            toolCalls={toolCalls}
            onToolCallsChange={setToolCalls}
          />
          {!chatOpen && <MaggiePortrait unread={unreadChat || unreadCount > 0} count={unreadCount} onClick={handleChatToggle} />}
          <MaggieInterruptionHost chatOpen={chatOpen} onOpenChat={handleOpenChatFromInterruption} />
        </Box>
      </RALayout>
    </ChatContext.Provider>
  )
}
