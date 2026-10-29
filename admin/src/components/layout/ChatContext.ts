import { createContext, useContext } from 'react'

export type SidebarTab = 'chat' | 'mind'

interface ChatContextValue {
  chatOpen: boolean
  sidebarTab: SidebarTab
  onChatToggle: () => void
  unreadChat: boolean
  /** How many of Maggie's messages are unread (server side); 0 when only the dot is known. */
  unreadCount: number
  onVoiceMessage: (text: string) => void
}

export const ChatContext = createContext<ChatContextValue>({
  chatOpen: false,
  sidebarTab: 'chat',
  onChatToggle: () => {},
  unreadChat: false,
  unreadCount: 0,
  onVoiceMessage: () => {},
})

export const useChatContext = () => useContext(ChatContext)
